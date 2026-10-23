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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;

/**
 * S3 gate-c1 F-A (MC 11163): unit tests for {@link AccountingNativeSqlExecutor} — the module-local execution
 * seam every slice read now runs through. The class mirrors JdbcTemplate's contracts on jdbc primitives;
 * these tests give that mirror red-capability at the unit level (DB-free, mocked jdbc objects): positional
 * bind order and the null-bind contract, the RowMapper iteration, the queryForObject size/exception
 * contract, and SQLException translation into Spring DataAccessExceptions.
 */
@ExtendWith(MockitoExtension.class)
class AccountingNativeSqlExecutorTest {

    private static final String SQL = "select v from somewhere where a = ? and b = ?";

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement statement;
    @Mock
    private ResultSet resultSet;

    private AccountingNativeSqlExecutor executor;

    @BeforeEach
    void setUp() {
        // built after Mockito injects the mocks
        this.executor = new AccountingNativeSqlExecutor(this.dataSource);
    }

    private void given(String sql) throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
    }

    @Test
    void positionalParametersBindInOrderOneBasedAndTheConnectionIsReleased() throws SQLException {
        given(SQL);
        when(resultSet.next()).thenReturn(false);

        List<String> rows = executor.queryForList(SQL, Arrays.asList("first", 7L), (rs, rowNum) -> "unused");

        assertEquals(List.of(), rows);
        verify(statement).setObject(1, "first");
        verify(statement).setObject(2, 7L);
        verify(connection).close();
    }

    @Test
    void aNullParameterBindsAsNullInsteadOfBeingSkipped() throws SQLException {
        given(SQL);
        when(resultSet.next()).thenReturn(false);

        executor.queryForList(SQL, Arrays.asList(null, "second"), (rs, rowNum) -> "unused");

        verify(statement).setNull(1, Types.NULL);
        verify(statement).setObject(2, "second");
    }

    @Test
    void everyRowIsMappedInIterationOrderWithZeroBasedRowNumbers() throws SQLException {
        given(SQL);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("v")).thenReturn("one", "two");

        List<String> rows = executor.queryForList(SQL, List.of(), (rs, rowNum) -> rowNum + ":" + rs.getString("v"));

        assertEquals(List.of("0:one", "1:two"), rows);
    }

    @Test
    void queryForObjectOnNoRowsThrowsTheSameEmptyResultContractTheServicesCatch() throws SQLException {
        given(SQL);
        when(resultSet.next()).thenReturn(false);

        assertThrows(EmptyResultDataAccessException.class,
                () -> executor.queryForObject(SQL, List.of(), (rs, rowNum) -> "never"));
    }

    @Test
    void queryForObjectOnMoreThanOneRowThrowsIncorrectResultSize() throws SQLException {
        given(SQL);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("v")).thenReturn("one", "two");

        assertThrows(IncorrectResultSizeDataAccessException.class,
                () -> executor.queryForObject(SQL, List.of(), (rs, rowNum) -> rs.getString("v")));
    }

    @Test
    void sqlExceptionsAreTranslatedIntoSpringDataAccessExceptions() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(SQL)).thenReturn(statement);
        // SQLState class 23 = integrity constraint violation -> DataIntegrityViolationException (same
        // SQLErrorCodeSQLExceptionTranslator chain JdbcTemplate runs, with its SQLState fallback here)
        when(statement.executeQuery()).thenThrow(new SQLException("duplicate key", "23505", 1));

        assertThrows(DataIntegrityViolationException.class,
                () -> executor.queryForList(SQL, List.of(), (rs, rowNum) -> "never"));
    }
}
