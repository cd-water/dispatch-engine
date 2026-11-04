package com.ridematching.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冒烟测试：上下文能启动，共享平台的自动配置被拾取。在运行任何更昂贵的东西之前，抓住常见
 * 的装配错误（缺少 bean、错误的属性、冲突的配置）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BillingServiceApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("application context loads")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("platform observability defaults are auto-configured")
    void observabilityDefaultsApplied() {
        assertThat(context.containsBean("percentileHistogramFilter")).isTrue();
    }
}
