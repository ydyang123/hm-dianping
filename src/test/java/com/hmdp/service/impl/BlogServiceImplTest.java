package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlogServiceImplTest {

    private static final Long USER_ID = 7L;

    private BlogServiceImpl blogService;
    private BlogMapper blogMapper;
    private IFollowService followService;
    private IUserService userService;
    private ZSetOperations<String, String> zSetOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        blogMapper = mock(BlogMapper.class);
        followService = mock(IFollowService.class);
        userService = mock(IUserService.class);

        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        zSetOperations = mock(ZSetOperations.class);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        blogService = new BlogServiceImpl();
        ReflectionTestUtils.setField(blogService, "baseMapper", blogMapper);
        ReflectionTestUtils.setField(blogService, "followService", followService);
        ReflectionTestUtils.setField(blogService, "userService", userService);
        ReflectionTestUtils.setField(blogService, "stringRedisTemplate", stringRedisTemplate);

        UserDTO currentUser = new UserDTO();
        currentUser.setId(USER_ID);
        UserHolder.saveUser(currentUser);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveBlogPushesBlogIdToEveryFollower() {
        Blog blog = new Blog();
        blog.setId(99L);
        when(blogMapper.insert(blog)).thenReturn(1);

        Follow firstFollower = new Follow();
        firstFollower.setUserId(2L);
        Follow secondFollower = new Follow();
        secondFollower.setUserId(3L);
        QueryChainWrapper<Follow> followQuery = mock(QueryChainWrapper.class);
        when(followService.query()).thenReturn(followQuery);
        when(followQuery.eq("follow_user_id", USER_ID)).thenReturn(followQuery);
        when(followQuery.list()).thenReturn(Arrays.asList(firstFollower, secondFollower));

        Result result = blogService.saveBlog(blog);

        assertTrue(result.getSuccess());
        assertEquals(99L, result.getData());
        verify(zSetOperations).add(eq("feed:2"), eq("99"), anyDouble());
        verify(zSetOperations).add(eq("feed:3"), eq("99"), anyDouble());
    }

    @Test
    void queryBlogByIdFillsCurrentUsersLikeStatus() {
        Blog blog = new Blog();
        blog.setId(99L);
        blog.setUserId(10L);
        when(blogMapper.selectById(99L)).thenReturn(blog);

        User author = new User();
        author.setNickName("作者");
        author.setIcon("icon.png");
        when(userService.getById(10L)).thenReturn(author);
        when(zSetOperations.score("blog:liked:99", USER_ID.toString())).thenReturn(1D);

        Result result = blogService.queryBlogById(99L);

        Blog resultBlog = (Blog) result.getData();
        assertEquals("作者", resultBlog.getName());
        assertEquals("icon.png", resultBlog.getIcon());
        assertTrue(resultBlog.getIsLike());
    }
}
