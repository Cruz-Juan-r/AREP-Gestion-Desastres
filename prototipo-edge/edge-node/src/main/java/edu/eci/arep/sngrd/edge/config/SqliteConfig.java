package edu.eci.arep.sngrd.edge.config;

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
 * Local durable store: a SQLite file at {@code EDGE_DB} in WAL mode with
 * {@code synchronous=NORMAL} (same settings as the Python edge). SQLite has a single
 * writer, so the pool holds exactly one connection: every access is serialised, which
 * is what the Python store achieved with its lock.
 */
@Configuration
public class SqliteConfig {

    @Bean(destroyMethod = "close")
    public DataSource dataSource(EdgeProperties props) {
        return sqliteDataSource(Path.of(props.db()), "edge-sqlite");
    }

    public static HikariDataSource sqliteDataSource(Path file, String poolName) {
        Path path = file.toAbsolutePath();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName(poolName);
        cfg.setJdbcUrl("jdbc:sqlite:" + path + "?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000");
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        return new HikariDataSource(cfg);
    }
}
