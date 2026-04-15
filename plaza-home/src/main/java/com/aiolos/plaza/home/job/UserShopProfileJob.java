package com.aiolos.plaza.home.job;

import com.aiolos.plaza.home.service.impl.UserProfileShopSearchServiceImpl;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 用户画像缓存构建任务
 */
@Slf4j
@Component
public class UserShopProfileJob {

    private final UserProfileShopSearchServiceImpl userProfileShopSearchService;

    @Value("${home.user-profile.job.lookback-days:180}")
    private int lookbackDays;

    @Value("${home.user-profile.job.max-users:1500}")
    private int maxUsers;

    public UserShopProfileJob(UserProfileShopSearchServiceImpl userProfileShopSearchService) {
        this.userProfileShopSearchService = userProfileShopSearchService;
    }

    /**
     * 构建最近活跃用户的画像缓存
     * 建议 cron：0 0/15 * * * ?
     */
    @XxlJob("homeUserShopProfileBuildJob")
    public void homeUserShopProfileBuildJob() {
        long start = System.currentTimeMillis();
        log.info("开始执行用户画像缓存构建任务, lookbackDays={}, maxUsers={}", lookbackDays, maxUsers);
        try {
            int refreshCount = userProfileShopSearchService.rebuildRecentUserProfileCache(lookbackDays, maxUsers);
            log.info("用户画像缓存构建任务完成, refreshCount={}, cost={}ms", refreshCount, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("用户画像缓存构建任务执行异常", e);
        }
    }
}
