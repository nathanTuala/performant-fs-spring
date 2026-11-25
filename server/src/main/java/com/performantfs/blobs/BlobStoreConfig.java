package com.performantfs.blobs;

import com.performantfs.config.AppProperties;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class BlobStoreConfig {

    @Bean
    LocalBlobStore blobStore(AppProperties props) throws IOException {
        return new LocalBlobStore(props.dataDir());
    }
}
