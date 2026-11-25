package com.hiking.gymsaas.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启用定时任务（未支付订单过期回收）；bootstrap 探针环境不调度业务任务。 */
@Configuration
@Profile("!bootstrap")
@EnableScheduling
public class SchedulingConfiguration { }
