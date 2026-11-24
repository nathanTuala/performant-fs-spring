package com.performantfs.web;

import com.performantfs.config.AppProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the built web app in production, so one process and one origin serve both the UI and
 * the API. In development {@code pfs.web-dir} is unset and Vite serves the UI instead.
 */
@Configuration
class WebAppConfig implements WebMvcConfigurer {

    private final Path webDir;

    WebAppConfig(AppProperties props) {
        this.webDir = props.webDir() != null && Files.isRegularFile(props.webDir().resolve("index.html")) ? props.webDir() : null;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (webDir == null) return;
        String root = webDir.toUri().toString();
        // Vite fingerprints everything under assets/, so those files never change.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(root + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations(root)
                .setCacheControl(CacheControl.noCache()); // index.html must pick up new deploys at once
    }

    /** The client router's URLs load the app shell, so refresh and deep links work. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (webDir == null) return;
        for (String route : new String[] {"/", "/trash", "/search", "/folder/{id}"}) {
            registry.addViewController(route).setViewName("forward:/index.html");
        }
    }
}
