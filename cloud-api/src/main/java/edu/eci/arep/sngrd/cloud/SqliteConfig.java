package edu.eci.arep.sngrd.cloud;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Consolidated store of the prototype: SQLite (WAL, synchronous=NORMAL) with
 * {@code event_id} as primary key. In production it would be PostgreSQL/PostGIS.
 * One pooled connection because SQLite has a single writer.
 */
@Configuration
public class SqliteConfig {

    @Bean(destroyMethod = "close")
    public DataSource dataSource(CloudProperties props) {
        Path path = Path.of(props.db()).toAbsolutePath();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("cloud-sqlite");
        cfg.setJdbcUrl("jdbc:sqlite:" + path + "?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000");
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        return new HikariDataSource(cfg);
    }
}
