package com.proofflow.business.cases;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.sql.SQLException;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "proofflow.cases.repository", havingValue = "mysql")
class MySqlCaseConfiguration {
    @Bean
    CaseRepository mySqlCaseRepository(Environment environment) {
        String url = environment.getRequiredProperty("proofflow.cases.mysql.url");
        if (!url.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("proofflow.cases.mysql.url must start with jdbc:mysql://");
        }

        ClassLoader loader = ClassUtils.getDefaultClassLoader();
        Class<? extends DataSource> type;
        try {
            type = ClassUtils.forName("com.mysql.cj.jdbc.MysqlDataSource", loader).asSubclass(DataSource.class);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("Add MySQL Connector/J to the application classpath", error);
        }

        DataSource dataSource = DataSourceBuilder.create(loader).type(type)
            .url(url)
            .username(environment.getRequiredProperty("proofflow.cases.mysql.user"))
            .password(environment.getRequiredProperty("proofflow.cases.mysql.password"))
            .build();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SELECT 1 FROM case_records LIMIT 0");
            statement.execute("SELECT 1 FROM case_audit_entries LIMIT 0");
        } catch (SQLException error) {
            throw new IllegalStateException("Could not open the MySQL case repository. Check the connection and V001 migration.", error);
        }
        return new MySqlCaseRepository(dataSource);
    }
}
