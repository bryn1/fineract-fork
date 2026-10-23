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
package org.apache.fineract.accounting.financialactivityaccount.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.fineract.accounting.common.AccountingDropdownReadPlatformService;
import org.apache.fineract.accounting.financialactivityaccount.data.FinancialActivityAccountData;
import org.apache.fineract.accounting.financialactivityaccount.exception.FinancialActivityAccountNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S4 gate PASS C pins (MC 11183): characterization of the two FinancialActivityAccount read queries at
 * the DataSource-level JDBC seam (same capture-below-the-mechanism design as the GLClosure pins — the
 * assertions must survive the S4c move to AccountingNativeSqlExecutor byte-identically; only the
 * service-construction helper is allowed to change).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FinancialActivityAccountReadPlatformServiceImplCharacterizationTest {

    // byte-identical copy of the FinancialActivityAccountMapper schema construction (constructor of the
    // mapper: three appended fragments, no parameters)
    private static final String SCHEMA = " faa.id as id, faa.financial_activity_type as financialActivityId, glaccount.id as glAccountId,glaccount.name as glAccountName,glaccount.gl_code as glCode  "
            + " from acc_gl_financial_activity_account faa " + " join acc_gl_account glaccount on glaccount.id = faa.gl_account_id";

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private AccountingDropdownReadPlatformService accountingDropdownReadPlatformService;

    private PreparedStatement statement;
    private final String[] capturedSql = new String[1];
    private final Map<Integer, Object> binds = new HashMap<>();

    // the single line S4c's peel commit is allowed to change (mechanism injection; assertions may not move)
    private FinancialActivityAccountReadPlatformServiceImpl newService() {
        return new FinancialActivityAccountReadPlatformServiceImpl(new JdbcTemplate(this.dataSource),
                this.accountingDropdownReadPlatformService);
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

    private void oneMappedRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        // JdbcSupport.getLong/getInteger dispatch via JdbcUtils.getResultSetValue(rs, findColumn(..)) —
        // index-based reads (spring-jdbc 7), so the stubs live on the column INDEXES
        when(rs.findColumn("id")).thenReturn(1);
        when(rs.findColumn("glAccountId")).thenReturn(2);
        when(rs.findColumn("financialActivityId")).thenReturn(3);
        when(rs.getLong(1)).thenReturn(4L);
        when(rs.getLong(2)).thenReturn(77L);
        when(rs.getInt(3)).thenReturn(101);
        when(rs.getString("glAccountName")).thenReturn("Cash at Main Vault");
        when(rs.getString("glCode")).thenReturn("1101");
        when(this.statement.executeQuery()).thenReturn(rs);
    }

    @Test
    void retrieveAllRunsThePlainSchemaAndMapsAccountActivityAndGlAccount() throws Exception {
        oneMappedRow();

        List<FinancialActivityAccountData> rows = newService().retrieveAll();

        assertEquals("select " + SCHEMA, this.capturedSql[0]);
        assertTrue(this.binds.isEmpty(), "retrieveAll binds nothing, got: " + this.binds);
        assertEquals(1, rows.size());
        FinancialActivityAccountData data = rows.get(0);
        assertEquals(4L, data.getId());
        assertEquals(101, data.getFinancialActivityData().getId());
        assertEquals(77L, data.getGlAccountData().getId());
        assertEquals("Cash at Main Vault", data.getGlAccountData().getName());
        assertEquals("1101", data.getGlAccountData().getGlCode());
    }

    @Test
    void retrieveByIdAppendsTheIdPredicateWithOnePositionalBindAndMapsTheRow() throws Exception {
        oneMappedRow();

        FinancialActivityAccountData data = newService().retrieve(4L);

        assertEquals("select " + SCHEMA + " where faa.id=?", this.capturedSql[0]);
        assertEquals(Map.of(1, 4L), this.binds);
        assertEquals(4L, data.getId());
        assertEquals(101, data.getFinancialActivityData().getId());
    }

    @Test
    void retrieveByIdOnEmptyResultMapsToFinancialActivityAccountNotFound() throws Exception {
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        when(this.statement.executeQuery()).thenReturn(empty);

        assertThrows(FinancialActivityAccountNotFoundException.class, () -> newService().retrieve(9999L));
    }
}
