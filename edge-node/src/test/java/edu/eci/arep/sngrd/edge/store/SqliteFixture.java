package edu.eci.arep.sngrd.edge.store;

import com.zaxxer.hikari.HikariDataSource;
import edu.eci.arep.sngrd.edge.config.SqliteConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Path;

/** A real SQLite file (same settings as production) with the edge store on top. */
final class SqliteFixture implements AutoCloseable {

    final Path file;
    final HikariDataSource dataSource;
    final JdbcTemplate jdbc;
    final EdgeStore store;

    SqliteFixture(Path dir) {
        this.file = dir.resolve("edge.db");
        this.dataSource = SqliteConfig.sqliteDataSource(file, "test-sqlite");
        this.jdbc = new JdbcTemplate(dataSource);
        this.store = new EdgeStore(jdbc, new DataSourceTransactionManager(dataSource));
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
