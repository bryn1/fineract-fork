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
package org.apache.fineract.accounting.closure.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.fineract.accounting.closure.data.GLClosureData;
import org.apache.fineract.accounting.closure.exception.GLClosureNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.apache.fineract.accounting.closure.domain.GLClosureReadRepository;
import org.apache.fineract.accounting.common.AccountingNativeSqlExecutor;

/**
 * S4 gate PASS C pins (MC 11183): characterization of the two GLClosure read queries at the JDBC seam —
 * captured SQL string (byte-identical to what the service builds today), positional binds, row-mapping,
 * and the empty-result contract. The capture points are deliberately BELOW the execution mechanism:
 * the mocks sit on DataSource/Connection/PreparedStatement/ResultSet, so the SAME assertions must stay
 * green when S4c moves execution from the injected JdbcTemplate to AccountingNativeSqlExecutor (the
 * peel commits may only touch the service-construction helper below, never an assertion).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GLClosureReadPlatformServiceImplCharacterizationTest {

    // byte-identical copy of GLClosureMapper.schema() (pins the schema as a literal, S4c may not touch it)
    private static final String SCHEMA = " glClosure.id as id, glClosure.office_id as officeId,office.name as officeName ,glClosure.closing_date as closingDate,"
            + " glClosure.is_deleted as isDeleted, creatingUser.id as creatingUserId,creatingUser.username as creatingUserName,"
            + " updatingUser.id as updatingUserId,updatingUser.username as updatingUserName, glClosure.created_date as createdDate,"
            + " glClosure.lastmodified_date as updatedDate, glClosure.comments as comments "
            + " from acc_gl_closure as glClosure, m_appuser as creatingUser, m_appuser as updatingUser,m_office as office"
            + " where glClosure.createdby_id=creatingUser.id and "
            + " glClosure.lastmodifiedby_id=updatingUser.id and glClosure.office_id=office.id";

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;

    private PreparedStatement statement;
    private final String[] capturedSql = new String[1];
    private final Map<Integer, Object> binds = new HashMap<>();

    // the single line S4c's peel commit is allowed to change (mechanism injection; assertions may not move)
    private GLClosureReadPlatformServiceImpl newService() {
        return new GLClosureReadPlatformServiceImpl(
                new GLClosureReadRepository(new AccountingNativeSqlExecutor(this.dataSource)));
    }

    @BeforeEach
    void setUp() throws Exception {
        this.statement = mock(PreparedStatement.class, invocation -> {
            if (invocation.getMethod().getName().startsWith("set")) {
                Object[] args = invocation.getArguments();
                if (args.length >= 2 && args[0] instanceof Integer) {
                    this.binds.put((Integer) args[0], args[1]);
                }
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        when(this.dataSource.getConnection()).thenReturn(this.connection);
        when(this.connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            this.capturedSql[0] = invocation.getArgument(0);
            return this.statement;
        });
    }

    private ResultSet oneRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong("id")).thenReturn(9L);
        when(rs.getLong("officeId")).thenReturn(3L);
        when(rs.getString("officeName")).thenReturn("Head Office");
        when(rs.getDate("closingDate")).thenReturn(Date.valueOf(LocalDate.of(2024, 6, 15)));
        when(rs.getBoolean("isDeleted")).thenReturn(false);
        when(rs.getDate("createdDate")).thenReturn(Date.valueOf(LocalDate.of(2024, 6, 1)));
        when(rs.getDate("updatedDate")).thenReturn(Date.valueOf(LocalDate.of(2024, 6, 2)));
        when(rs.getLong("creatingUserId")).thenReturn(1L);
        when(rs.getString("creatingUserName")).thenReturn("admin");
        when(rs.getLong("updatingUserId")).thenReturn(2L);
        when(rs.getString("updatingUserName")).thenReturn("maker");
        when(rs.getString("comments")).thenReturn("books closed");
        when(this.statement.executeQuery()).thenReturn(rs);
        return rs;
    }

    @Test
    void retrieveAllWithOfficeFilterAppendsThePredicateAndBindsOnePositionalValue() throws Exception {
        oneRow();

        List<GLClosureData> rows = newService().retrieveAllGLClosures(7L);

        assertEquals("select " + SCHEMA + " and glClosure.is_deleted = false and glClosure.office_id = ?"
                + " order by glClosure.closing_date desc", this.capturedSql[0]);
        assertEquals(Map.of(1, 7L), this.binds);
        assertEquals(1, rows.size());
    }

    @Test
    void retrieveAllWithoutOfficeFilterOmitsThePredicateAndBindsNothing() throws Exception {
        oneRow();

        newService().retrieveAllGLClosures(null);

        assertEquals("select " + SCHEMA + " and glClosure.is_deleted = false order by glClosure.closing_date desc", this.capturedSql[0]);
        assertTrue(this.binds.isEmpty(), "no office predicate -> no binds, got: " + this.binds);
    }

    @Test
    void retrieveByIdUsesTheIdPredicateAndMapsEveryFieldOfTheRow() throws Exception {
        oneRow();

        GLClosureData data = newService().retrieveGLClosureById(9L);

        assertEquals("select " + SCHEMA + " and glClosure.id = ?", this.capturedSql[0]);
        assertEquals(Map.of(1, 9L), this.binds);
        assertEquals(9L, data.getId());
        assertEquals(3L, data.getOfficeId());
        assertEquals("Head Office", data.getOfficeName());
        assertEquals(LocalDate.of(2024, 6, 15), data.getClosingDate());
        assertFalse(data.isDeleted());
        assertEquals(LocalDate.of(2024, 6, 1), data.getCreatedDate());
        assertEquals(LocalDate.of(2024, 6, 2), data.getLastUpdatedDate());
        assertEquals(1L, data.getCreatedByUserId());
        assertEquals("admin", data.getCreatedByUsername());
        assertEquals(2L, data.getLastUpdatedByUserId());
        assertEquals("maker", data.getLastUpdatedByUsername());
        assertEquals("books closed", data.getComments());
    }

    @Test
    void retrieveByIdOnEmptyResultMapsToGlClosureNotFound() throws Exception {
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        when(this.statement.executeQuery()).thenReturn(empty);

        assertThrows(GLClosureNotFoundException.class, () -> newService().retrieveGLClosureById(4242L));
    }
}
