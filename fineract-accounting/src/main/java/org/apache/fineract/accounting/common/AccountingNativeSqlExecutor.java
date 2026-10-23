/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.accounting.common;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.stereotype.Component;

/**
 * Module-local execution seam for the raw SQL of the accounting read queries (MC 11163, S3 peel; S4-T
 * converged research/05): the services no longer run SQL themselves and no accounting-owned dotted
 * JdbcTemplate SQL path exists (module probe 0). Single named residual: one JdbcTemplate instance in
 * ProvisioningEntriesReadRepository serves only as the PaginationHelper fetchPage hand-off argument —
 * core-infra execution of count+page, not an accounting SQL path.
 *
 * <p>
 * It is provider-agnostic on purpose (this module excludes Hibernate and compiles JPA against the EclipseLink
 * jakarta API). Every contract mirrors what Spring JdbcTemplate itself does with the same collaborators: the
 * connection comes from {@link DataSourceUtils} (same transaction-exposure contract), positional parameters
 * bind the way StatementCreatorUtils binds them for these scalar types, the SAME {@link RowMapper} /
 * {@link ResultSetExtractor} iteration drives the java.sql.ResultSet, and SQLExceptions are translated by the
 * same {@link SQLErrorCodeSQLExceptionTranslator} built on the same DataSource. Moved SQL and mappers are
 * byte-identical; this class adds no query of its own.
 * </p>
 */
@Component
public class AccountingNativeSqlExecutor {

    private final DataSource dataSource;
    private final SQLExceptionTranslator exceptionTranslator;

    public AccountingNativeSqlExecutor(DataSource dataSource) {
        this.dataSource = dataSource;
        this.exceptionTranslator = new SQLErrorCodeSQLExceptionTranslator(dataSource);
    }

    /**
     * Iterates every row through the mapper exactly as JdbcTemplate.query does (row numbers starting at 0).
     */
    public <T> List<T> queryForList(String sql, List<?> parameters, RowMapper<T> rowMapper) {
        return execute(sql, parameters, resultSet -> {
            final List<T> rows = new ArrayList<>();
            int rowNum = 0;
            while (resultSet.next()) {
                rows.add(rowMapper.mapRow(resultSet, rowNum++));
            }
            return rows;
        });
    }

    /**
     * Single result with JdbcTemplate.queryForObject's contract: no row yields
     * {@link EmptyResultDataAccessException}, more than one {@link IncorrectResultSizeDataAccessException}.
     */
    public <T> T queryForObject(String sql, List<?> parameters, RowMapper<T> rowMapper) {
        final List<T> rows = queryForList(sql, parameters, rowMapper);
        if (rows.isEmpty()) {
            throw new EmptyResultDataAccessException(1);
        }
        if (rows.size() > 1) {
            throw new IncorrectResultSizeDataAccessException(1, rows.size());
        }
        return rows.get(0);
    }

    /**
     * Hands the ResultSet to the extractor exactly once, even when it is empty — JdbcTemplate.query's
     * extractor contract.
     */
    public <T> T extract(String sql, List<?> parameters, ResultSetExtractor<T> extractor) {
        return execute(sql, parameters, extractor);
    }

    private <T> T execute(String sql, List<?> parameters, ResultSetExtractor<T> extractor) {
        Connection connection = DataSourceUtils.getConnection(this.dataSource);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) {
                final Object parameter = parameters.get(i);
                if (parameter == null) {
                    statement.setNull(i + 1, Types.NULL);
                } else {
                    statement.setObject(i + 1, parameter);
                }
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return extractor.extractData(resultSet);
            }
        } catch (final SQLException exception) {
            throw translate(this.exceptionTranslator, sql, exception);
        } finally {
            DataSourceUtils.releaseConnection(connection, this.dataSource);
        }
    }

    private static DataAccessException translate(SQLExceptionTranslator translator, String sql, SQLException exception) {
        DataAccessException translated = translator.translate("SQL query", sql, exception);
        return translated != null ? translated : new UncategorizedSQLException("SQL query", sql, exception);
    }
}
