package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {


    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    Result sendCode(String phone, HttpSession session);


    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 登出功能
     * @return
     */
    void logout(String token);

    /**
     * 执行签到
     * @return
     */
    Result sign();

    /**
     * 查询今日是否已签到
     * @return
     */
    Result signStatus();

    /**
     * 查询指定年份的签到日历
     * @param year 年份
     * @return
     */
    Result signCalendar(Integer year);

    /**
     * 查询连续签到天数
     * @return
     */
    Result signCount();
}
