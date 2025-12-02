package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    /**
     * 查询笔记
     * @param id 笔记id
     * @return Result
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            log.debug("笔记不存在");
            return Result.fail("笔记不存在！");
        }
        log.info("查询主页笔记：{}", blog);
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }



    /**
     * 查询热门笔记
     * @param current 当前页
     * @return Result
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 点赞
     * @param id 博客id
     * @return Result
     */
    @Override
    public Result likeBlog(Long id) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        if(userId == null){
            return Result.fail("请先登录！");
        }
        //判断是否已经点过赞，使用redis set集合
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if(success){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else {
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if(success){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询所有点赞博客的用户
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        // 查询Top5的点赞用户 zrange key 0 4
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }


    /**
     * 查询我的笔记
     * @param current
     * @return
     */
    @Override
    public Result queryMyBlog(Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 查询用户笔记
     * @param current
     * @param id
     * @return
     */
    @Override
    public Result queryBlogByUserId(Integer current, Long id) {
        // 1. 根据用户 ID 查询博客
        Page<Blog> page = query()
                .eq("user_id", id)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));

        // 2. 获取当前页数据
        List<Blog> records = page.getRecords();

        // 3. 返回结果
        return Result.ok(records);
    }

    /**
     * 保存笔记
     * @param blog 笔记
     * @return Result
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("笔记保存失败！");
        }
        //查询笔记作者粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        if(follows == null){
            return Result.ok(blog.getId());
        }
        //推送笔记
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = FEED_KEY  + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
            log.info("推送笔记：{}，目标：{}", blog.getId(), userId);
        }


        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 查询笔记
     * @param max 最大时间
     * @param offset 偏移量
     * @return Result
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1、查询收件箱
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        // ZREVRANGEBYSCORE key Max Min LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        log.info("查询收件箱：{}", typedTuples);
        // 2、判断收件箱中是否有数据
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        // 3、收件箱中有数据，则解析数据: blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 记录当前最小值
        int os = 1; // 偏移量offset，用来计数
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 获取分数（时间戳）
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                // 当前时间等于最小时间，偏移量+1
                os++;
            } else {
                // 当前时间不等于最小时间，重置
                minTime = time;
                os = 1;
            }
        }

        // 4、根据id查询blog（使用in查询的数据是默认按照id升序排序的，这里需要使用我们自己指定的顺序排序）
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = this.list(new LambdaQueryWrapper<Blog>().in(Blog::getId, ids)
                .last("ORDER BY FIELD(id," + idStr + ")"));
        // 设置blog相关的用户数据，是否被点赞等属性值
        for (Blog blog : blogs) {
            // 查询blog有关的用户
            queryBlogUser(blog);
            // 查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 5、封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);

        log.info("查询收件箱结果：{}", scrollResult);
        return Result.ok(scrollResult);
    }

    /**
     * 查询用户
     * @param blog 博客
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 查询是否点赞
     * @param blog 博客
     */
    private void isBlogLiked(Blog blog) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        if(userId == null){
            return;
        }
        //判断是否已经点过赞，使用redis set集合
        String key = BLOG_LIKED_KEY + blog.getId();
        try {
            // 尝试以 ZSet 方式查询分数
            Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
            blog.setIsLike(score != null);
        } catch (Exception e) {
            // 3. 捕获异常，判断是否为类型不匹配 (WRONGTYPE)
            // 不同的 Redis 客户端或 Spring 版本，异常层级可能不同，通常检查 message 即可
            if (e.getMessage().contains("WRONGTYPE") ||
                    (e.getCause() != null && e.getCause().getMessage().contains("WRONGTYPE"))) {

                log.warn("检测到 Redis Key 类型冲突 (期望 ZSet，实际为 Set/String)，正在自动清理 Key: {}",  key);

                // 4. 核心修复：直接删除脏数据
                stringRedisTemplate.delete(key);

                // 5. 既然数据删了，当前状态肯定视为“未点赞”
                blog.setIsLike(false);
            } else {
                // 如果是其他错误（如 Redis 宕机），则抛出
                throw e;
            }
        };
    }

}
