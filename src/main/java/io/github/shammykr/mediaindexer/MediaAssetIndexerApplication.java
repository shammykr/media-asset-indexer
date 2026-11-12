package io.github.shammykr.mediaindexer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MediaAssetIndexerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaAssetIndexerApplication.class, args);
    }
}
