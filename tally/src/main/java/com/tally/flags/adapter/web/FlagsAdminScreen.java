package com.tally.flags.adapter.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the flags admin screen at its address (ADR-055). */
@Configuration
class FlagsAdminScreen implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/admin/flags/").setViewName("forward:/admin/flags/index.html");
        registry.addRedirectViewController("/admin/flags", "/admin/flags/");
    }
}
