package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private static final DateTimeFormatter SIGN_KEY_SUFFIX_FORMATTER = DateTimeFormatter.ofPattern(":yyyyMM");

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 发送手机验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果手机号不正确，则输出日志
            return Result.fail("手机号格式不正确");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.将验证码保存到redis
        String codeKey = LOGIN_CODE_KEY + phone;
        stringRedisTemplate.opsForValue().set(codeKey, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        //返回成功
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式不正确");
        }
        // 3.从redis获取缓存的验证码进行校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();  // 从表单中获取用户输入的验证码
        if (cacheCode == null || !cacheCode.equals(code)){
            //4.不一致，报错
            return Result.fail("验证码错误");
        }
        //一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //5.判断用户是否存在
        if (user == null){
            //不存在，则创建
            user = createUserWithPhone(phone);
        }

        //6.将用户信息转换为UserDTO对象，只复制需要的字段，避免敏感信息泄露
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        //7.保存用户信息
        // 7.1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString();
        // 7.2.将User对象转为HashMap存储
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)   //值为null的字段直接不放进 Map
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 7.3.存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.4.设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    /**
     * 创建新用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName("用户" + RandomUtil.randomString(10));
        //2.保存用户
        save(user);
        //3.返回用户
        return user;
    }

    /**
     * 登出功能
     */
    @Override
    public void logout(String token) {
        // 实现登出功能
        //1.校验token,非空才处理
        if(StrUtil.isNotBlank(token)){
            //2.删除token
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        }
        //3.清理当前线程的Threadlocal
        UserHolder.removeUser();
    }

    /**
     * 当日签到
     */
    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDate today = LocalDate.now();
        String key = getSignKey(userId, today);
        stringRedisTemplate.opsForValue()
                .setBit(key, today.getDayOfMonth() - 1, true);
        return Result.ok();
    }

    /**
     * 查询当日签到状态
     */
    @Override
    public Result signStatus() {
        Long userId = UserHolder.getUser().getId();
        return Result.ok(hasSigned(userId, LocalDate.now()));
    }

    /**
     * 查询指定年份的签到日历
     */
    @Override
    public Result signCalendar(Integer year) {
        Long userId = UserHolder.getUser().getId();
        User user = getById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }

        LocalDate today = LocalDate.now();
        LocalDate registrationDate = user.getCreateTime() == null
                ? LocalDate.of(today.getYear(), 1, 1)
                : user.getCreateTime().toLocalDate();
        int selectedYear = year == null ? today.getYear() : year;
        if (selectedYear < registrationDate.getYear() || selectedYear > today.getYear()) {
            return Result.fail("年份超出可查询范围");
        }

        LocalDate rangeStart = selectedYear == registrationDate.getYear()
                ? registrationDate
                : LocalDate.of(selectedYear, 1, 1);
        LocalDate rangeEnd = selectedYear == today.getYear()
                ? today
                : LocalDate.of(selectedYear, 12, 31);
        Set<LocalDate> signedDateSet = querySignedDates(userId, selectedYear, rangeStart, rangeEnd);
        List<String> signedDates = new ArrayList<>(signedDateSet.size());
        signedDateSet.stream().sorted().forEach(date -> signedDates.add(date.toString()));

        Map<String, Object> calendar = new LinkedHashMap<>();
        calendar.put("year", selectedYear);
        calendar.put("minYear", registrationDate.getYear());
        calendar.put("registrationDate", registrationDate.toString());
        calendar.put("signedDates", signedDates);
        calendar.put("total", signedDates.size());
        calendar.put("longestStreak", calculateLongestStreak(signedDateSet, rangeStart, rangeEnd));
        if (selectedYear == today.getYear()) {
            calendar.put("currentStreak", calculateCurrentStreak(userId, today));
        }
        return Result.ok(calendar);
    }

    /**
     * 签到统计
     * @return
     */
    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        return Result.ok(calculateCurrentStreak(userId, LocalDate.now()));
    }

    private int calculateCurrentStreak(Long userId, LocalDate today) {
        LocalDate cursor = today;
        // 今天还未签到时，从昨天开始统计，保留此前的连续签到天数
        if (!hasSigned(userId, cursor)) {
            cursor = cursor.minusDays(1);
        }

        int count = 0;
        while (true) {
            int dayOfMonth = cursor.getDayOfMonth();
            Long signBits = getSignBits(userId, cursor, dayOfMonth);
            if (signBits == null || signBits == 0) {
                break;
            }

            int countInMonth = 0;
            while (countInMonth < dayOfMonth && (signBits & 1) == 1) {
                count++;
                countInMonth++;
                signBits >>>= 1;
            }
            if (countInMonth < dayOfMonth) {
                break;
            }

            // 本月截至 cursor 的每一天都已签到，继续统计上个月
            cursor = cursor.minusDays(dayOfMonth);
        }
        return count;
    }

    private Set<LocalDate> querySignedDates(Long userId, int year, LocalDate rangeStart, LocalDate rangeEnd) {
        Set<LocalDate> signedDates = new HashSet<>();
        YearMonth startMonth = YearMonth.from(rangeStart);
        YearMonth endMonth = YearMonth.from(rangeEnd);
        for (YearMonth month = startMonth; !month.isAfter(endMonth); month = month.plusMonths(1)) {
            int daysInMonth = month.lengthOfMonth();
            Long signBits = getSignBits(userId, month.atDay(1), daysInMonth);
            if (signBits == null || signBits == 0) {
                continue;
            }
            for (int day = 1; day <= daysInMonth; day++) {
                int shift = daysInMonth - day;
                if (((signBits >>> shift) & 1) == 1) {
                    LocalDate date = LocalDate.of(year, month.getMonthValue(), day);
                    if (!date.isBefore(rangeStart) && !date.isAfter(rangeEnd)) {
                        signedDates.add(date);
                    }
                }
            }
        }
        return signedDates;
    }

    private int calculateLongestStreak(Set<LocalDate> signedDates, LocalDate rangeStart, LocalDate rangeEnd) {
        int longest = 0;
        int current = 0;
        for (LocalDate date = rangeStart; !date.isAfter(rangeEnd); date = date.plusDays(1)) {
            if (signedDates.contains(date)) {
                current++;
                longest = Math.max(longest, current);
            } else {
                current = 0;
            }
        }
        return longest;
    }

    /**
     * 判断用户是否签到
     * @param userId
     * @param date
     * @return
     */
    private boolean hasSigned(Long userId, LocalDate date) {
        Boolean signed = stringRedisTemplate.opsForValue()
                .getBit(getSignKey(userId, date), date.getDayOfMonth() - 1);
        return Boolean.TRUE.equals(signed);
    }

    /**
     * 获取签到状态
     * @param userId
     * @param date
     * @return
     */
    private Long getSignBits(Long userId, LocalDate date, int dayOfMonth) {
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                getSignKey(userId, date),
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return null;
        }
        return result.get(0);
    }

    /**
     * 获取签到key
     * @param userId
     * @param date
     * @return
     */
    private String getSignKey(Long userId, LocalDate date) {
        return USER_SIGN_KEY + userId + date.format(SIGN_KEY_SUFFIX_FORMATTER);
    }
}
