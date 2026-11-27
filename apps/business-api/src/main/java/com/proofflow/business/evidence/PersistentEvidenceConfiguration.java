package com.proofflow.business.evidence;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.SQLException;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "proofflow.evidence.persistence", havingValue = "true")
class PersistentEvidenceConfiguration {
    @Bean
    MinioClient evidenceMinioClient(Environment environment) {
        var client = MinioClient.builder()
            .endpoint(environment.getRequiredProperty("proofflow.evidence.minio.endpoint"))
            .credentials(environment.getRequiredProperty("proofflow.evidence.minio.access-key"),
                environment.getRequiredProperty("proofflow.evidence.minio.secret-key"))
            .build();
        client.setTimeout(3000, 15000, 15000);
        return client;
    }

    @Bean
    EvidenceRepository persistentEvidenceRepository(@Qualifier("caseMySqlDataSource") DataSource dataSource,
                                                    MinioClient evidenceMinioClient, Environment environment) {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SELECT 1 FROM case_evidence LIMIT 0");
        } catch (SQLException error) {
            throw new IllegalStateException("Could not open the evidence metadata table. Apply the V002 migration.", error);
        }
        var objects = new MinioEvidenceObjectStore(evidenceMinioClient,
            environment.getProperty("proofflow.evidence.minio.bucket", "proofflow-evidence"));
        objects.ensureBucket();
        return new PersistentEvidenceRepository(new MySqlEvidenceMetadataRepository(dataSource), objects);
    }
}
