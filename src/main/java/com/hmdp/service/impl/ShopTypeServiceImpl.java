package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询商铺类型列表
     * @return
     */
    @Override
    public Result queryTypeList() {
        // opsForList写法
        // public static final String CACHE_SHOP_TYPE_KEY = "cache:shopType:";
        String key = CACHE_SHOP_TYPE_KEY;
        // 1. 从Redis查询 商铺类型缓存 , end:-1 表示取全部数据
        List<String> shopTypeJson = stringRedisTemplate.opsForList().range(key, 0, -1);
        // 2. 有就直接返回
        if (CollectionUtil.isNotEmpty(shopTypeJson)) {
            // 将每个 JSON 字符串单独反序列化
            List<ShopType> shopTypes = shopTypeJson.stream()
                    .map(str -> JSONUtil.toBean(str, ShopType.class))
                    .collect(Collectors.toList());
            Collections.sort(shopTypes, ((o1, o2) -> o1.getSort() - o2.getSort()));
            return Result.ok(shopTypes);
        }
        // 3. 没有就向数据库查询 MP的query()拿来用
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        // 4. 不存在，返回错误
        if (CollectionUtil.isEmpty(shopTypes)){
            // 将空值写入Redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return Result.fail("商铺类型不存在...");
        }
        // 5. 存在， 写入Redis，这里使用Redis的List类型，String类型，就是直接所有都写在一起，对内存开销比较大。
        // 要将List中的每个元素(元素类型ShopType) ，每个元素都要单独转成JSON，使用stream流的map映射
        // Hutools里的 BeanUtil.copyToList 本来想模仿UserService中的写法，
        // 传入一个CopyOptions的，但是setFieldValueEditor貌似只对beanToMap有效
        // 改用流的形式转换每个list元素
        List<String> shopTypesJson = shopTypes.stream()
                .map(shopType -> JSONUtil.toJsonStr(shopType))
                .collect(Collectors.toList());
        // 因为从数据库读出来的时候已经是按照顺序读出来的，这里想要维持顺序必须从右边push，类似队列
        stringRedisTemplate.delete(key); // 删除旧的缓存
        stringRedisTemplate.opsForList().rightPushAll(key, shopTypesJson);  // 将新的缓存写入Redis
        stringRedisTemplate.expire(key, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES); // 设置缓存有效期为1天

        return Result.ok(shopTypes);
    }
}
