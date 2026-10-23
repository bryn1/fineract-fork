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
package org.apache.fineract.accounting.provisioning.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.fineract.accounting.provisioning.data.LoanProductProvisioningEntryData;
import org.apache.fineract.accounting.provisioning.data.ProvisioningEntryData;
import org.apache.fineract.infrastructure.core.service.PaginationHelper;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.core.service.database.DatabaseTypeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.apache.fineract.accounting.common.AccountingNativeSqlExecutor;
import org.apache.fineract.accounting.provisioning.domain.ProvisioningEntriesReadRepository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S4 gate PASS C pins (MC 11183): characterization of the six dotted jdbcTemplate sites of
 * ProvisioningEntriesReadPlatformServiceImpl at the DataSource-level JDBC seam — captured SQL strings
 * (schemas copied byte-identically; the dialect collaborator is exercised for real on a MySQL-resolver
 * stub so dateDiff expands exactly as production MySQL does), positional binds, row mapping, the
 * two-query criteriaId chain, and the empty-result-to-null contracts. Capture points are BELOW the
 * execution mechanism: the S4c peel to AccountingNativeSqlExecutor must keep every assertion
 * byte-identical; only the service-construction helper may change. The two PaginationHelper hand-offs
 * are NOT part of the six sites and are deliberately unpinned (research/05 S4-T residual note).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProvisioningEntriesReadPlatformServiceImplCharacterizationTest {

    // byte-identical copies of the four mapper schemas (text blocks carried as-is, \s escapes included)
    private static final String LOAN_PRODUCTS_SCHEMA = "select (CASE WHEN loan.loan_type_enum=1 THEN mclient.office_id ELSE mgroup.office_id END) as office_id, loan.loan_type_enum, pcd.criteria_id as criteriaid, loan.product_id,loan.currency_code,"
            + "GREATEST(DATEDIFF(?, sch.duedate), 0) as numberofdaysoverdue,sch.duedate, pcd.category_id, pcd.provision_percentage,"
            + "loan.total_outstanding_derived as outstandingbalance, pcd.liability_account, pcd.expense_account from m_loan_repayment_schedule sch"
            + " LEFT JOIN m_loan loan on sch.loan_id = loan.id"
            + " JOIN m_loanproduct_provisioning_mapping lpm on lpm.product_id = loan.product_id"
            + " JOIN m_provisioning_criteria_definition pcd on pcd.criteria_id = lpm.criteria_id and "
            + "(pcd.min_age <= GREATEST(DATEDIFF(?, sch.duedate),0) and GREATEST(DATEDIFF(?, sch.duedate),0) <= pcd.max_age) and pcd.criteria_id is not null "
            + "LEFT JOIN m_client mclient ON mclient.id = loan.client_id "
            + "LEFT JOIN m_group mgroup ON mgroup.id = loan.group_id "
            + "where loan.loan_status_id=300 and sch.duedate = "
            + "(select MIN(sch1.duedate) from m_loan_repayment_schedule sch1 where sch1.loan_id=loan.id and sch1.completed_derived=false)";

    private static final String ENTRY_SCHEMA = """
             entry.id, entry.journal_entry_created, entry.createdby_id, entry.created_date, created.username as createduser,
            entry.lastmodifiedby_id, modified.username as modifieduser, entry.lastmodified_date
            from m_provisioning_history entry left JOIN m_appuser created ON created.id = entry.createdby_id
            left JOIN m_appuser modified ON modified.id = entry.lastmodifiedby_id\s""";

    private static final String ENTRY_SUM_SCHEMA = """
             entry.id, journal_entry_created, createdby_id, created_date, created.username as createduser,
            lastmodifiedby_id, modified.username as modifieduser, lastmodified_date, SUM(reserved.reseve_amount) as totalreserved
            from m_provisioning_history entry
            LEFT JOIN m_loanproduct_provisioning_entry reserved on entry.id = reserved.history_id
            left JOIN m_appuser created ON created.id = entry.createdby_id
            left JOIN m_appuser modified ON modified.id = entry.lastmodifiedby_id\s""";

    private static final String LPPE_SCHEMA = """
             entry.id, entry.history_id as historyId, office_id, entry.criteria_id as criteriaid, office.name as officename, product.name as productname, entry.product_id,
            category_id, category.category_name, liability.id as liabilityid, liability.gl_code as liabilitycode, liability.name as liabilityname,
            expense.id as expenseid, expense.gl_code as expensecode, expense.name as expensename, entry.currency_code, entry.overdue_in_days, entry.reseve_amount from m_loanproduct_provisioning_entry entry
            left join m_office office ON office.id = entry.office_id
            left join m_product_loan product ON product.id = entry.product_id
            left join m_provision_category category ON category.id = entry.category_id
            left join acc_gl_account liability ON liability.id = entry.liability_account
            left join acc_gl_account expense ON expense.id = entry.expense_account\s""";

    private static final String ID_DATE_SCHEMA = """
            select history1.id, history1.created_date from m_provisioning_history history1
            where history1.created_date = (select max(history2.created_date) from m_provisioning_history history2
            where history2.journal_entry_created='1')\s""";

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private DatabaseTypeResolver databaseTypeResolver;

    private final List<String> sqls = new ArrayList<>();
    private final List<Map<Integer, Object>> bindsPerStatement = new ArrayList<>();
    private final Deque<ResultSet> resultsToServe = new ArrayDeque<>();

    // the single line S4c's peel commit is allowed to change (mechanism injection; assertions may not move)
    private ProvisioningEntriesReadPlatformServiceImpl newService() {
        return new ProvisioningEntriesReadPlatformServiceImpl(new ProvisioningEntriesReadRepository(
                new AccountingNativeSqlExecutor(this.dataSource), new DatabaseSpecificSQLGenerator(this.databaseTypeResolver, null),
                new JdbcTemplate(this.dataSource), mock(PaginationHelper.class), mock(PaginationHelper.class)));
    }

    @BeforeEach
    void setUp() throws Exception {
        when(this.databaseTypeResolver.isMySQL()).thenReturn(true);
        when(this.dataSource.getConnection()).thenReturn(this.connection);
        when(this.connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            this.sqls.add(invocation.getArgument(0));
            Map<Integer, Object> binds = new HashMap<>();
            this.bindsPerStatement.add(binds);
            return mock(PreparedStatement.class, inner -> {
                if (inner.getMethod().getName().startsWith("set")) {
                    Object[] args = inner.getArguments();
                    if (args.length >= 2 && args[0] instanceof Integer) {
                        binds.put((Integer) args[0], args[1]);
                    }
                } else if (inner.getMethod().getName().equals("executeQuery")) {
                    return this.resultsToServe.isEmpty() ? emptyRs() : this.resultsToServe.poll();
                }
                return RETURNS_DEFAULTS.answer(inner);
            });
        });
    }

    private ResultSet emptyRs() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    private ResultSet entryRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong("id")).thenReturn(11L);
        when(rs.getBoolean("journal_entry_created")).thenReturn(true);
        when(rs.getLong("createdby_id")).thenReturn(1L);
        when(rs.getString("createduser")).thenReturn("admin");
        when(rs.getDate("created_date")).thenReturn(Date.valueOf(LocalDate.of(2024, 6, 15)));
        when(rs.getLong("lastmodifiedby_id")).thenReturn(2L);
        when(rs.getString("modifieduser")).thenReturn("maker");
        when(rs.getBigDecimal("totalreserved")).thenReturn(new BigDecimal("42.00"));
        return rs;
    }

    @Test
    void loanProductsProvisioningDataExpandsDateDiffFourTimesAndBindsTheFormattedDateThreeTimes() throws Exception {
        this.resultsToServe.add(entryRow());

        newService().retrieveLoanProductsProvisioningData(LocalDate.of(2024, 6, 15));

        assertEquals(LOAN_PRODUCTS_SCHEMA, this.sqls.get(0));
        assertEquals(Map.of(1, "2024-06-15", 2, "2024-06-15", 3, "2024-06-15"), this.bindsPerStatement.get(0));
    }

    @Test
    void loanProductsRowMapsEveryLabelReadOfTheEntryDto() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong("office_id")).thenReturn(3L);
        when(rs.getLong("product_id")).thenReturn(9L);
        when(rs.getString("currency_code")).thenReturn("USD");
        when(rs.getLong("numberofdaysoverdue")).thenReturn(31L);
        when(rs.getLong("category_id")).thenReturn(5L);
        when(rs.getBigDecimal("provision_percentage")).thenReturn(new BigDecimal("12.50"));
        when(rs.getBigDecimal("outstandingbalance")).thenReturn(new BigDecimal("1000.00"));
        when(rs.getLong("liability_account")).thenReturn(21L);
        when(rs.getLong("expense_account")).thenReturn(22L);
        when(rs.getLong("criteriaid")).thenReturn(7L);
        this.resultsToServe.add(rs);

        var rows = newService().retrieveLoanProductsProvisioningData(LocalDate.of(2024, 6, 15));

        LoanProductProvisioningEntryData data = List.copyOf(rows).get(0);
        assertNull(data.getHistoryId(), "historyId is pinned null: the mapper never sets it from the row");
        assertEquals(3L, data.getOfficeId());
        assertEquals(9L, data.getProductId());
        assertEquals("USD", data.getCurrencyCode());
        assertEquals(31L, data.getOverdueInDays());
        assertEquals(new BigDecimal("12.50"), data.getPercentage());
        assertEquals(new BigDecimal("1000.00"), data.getBalance());
        assertEquals(7L, data.getCriteriaId());
    }

    @Test
    void provisioningEntryDataByIdUsesTheGroupedSumSchemaAndMapsTheReservedTotal() throws Exception {
        this.resultsToServe.add(entryRow());

        ProvisioningEntryData data = newService().retrieveProvisioningEntryData(11L);

        assertEquals("select" + ENTRY_SUM_SCHEMA + " where entry.id = ? group by entry.id, created.username, modified.username",
                this.sqls.get(0));
        assertEquals(Map.of(1, 11L), this.bindsPerStatement.get(0));
        assertEquals(11L, data.getId());
        assertEquals(LocalDate.of(2024, 6, 15), data.getCreatedDate());
        assertEquals(new BigDecimal("42.00"), data.getReservedAmount());
    }

    @Test
    void provisioningEntryDataByDateAppendsPercentWildcardAndReturnsNullOnEmptyResult() throws Exception {
        this.resultsToServe.add(entryRow());
        ProvisioningEntryData found = newService().retrieveProvisioningEntryData("2024-06");
        assertEquals("select " + ENTRY_SCHEMA + " where entry.created_date like ? ", this.sqls.get(0));
        assertEquals(Map.of(1, "2024-06%"), this.bindsPerStatement.get(0));
        assertEquals(11L, found.getId());

        this.resultsToServe.add(emptyRs());
        assertNull(newService().retrieveProvisioningEntryData("2030-01"), "empty like-match logs and returns null (no throw escapes)");
    }

    @Test
    void byCriteriaIdChainsTheEntryLookupThenTheHistoryLookupAndAttachesTheEntries() throws Exception {
        ResultSet lpeRow = mock(ResultSet.class);
        when(lpeRow.next()).thenReturn(true, false);
        when(lpeRow.getLong("historyId")).thenReturn(11L);
        this.resultsToServe.add(lpeRow);
        this.resultsToServe.add(entryRow());

        ProvisioningEntryData data = newService().retrieveProvisioningEntryDataByCriteriaId(7L);

        assertEquals(List.of("select " + LPPE_SCHEMA + " where entry.criteria_id = ?",
                "select " + ENTRY_SCHEMA + " where entry.id = ?"), this.sqls);
        assertEquals(Map.of(1, 7L), this.bindsPerStatement.get(0));
        assertEquals(Map.of(1, 11L), this.bindsPerStatement.get(1), "second query binds the historyId read from the first row");
        assertEquals(11L, data.getId());
        assertTrue(data.getProvisioningEntries() != null && !data.getProvisioningEntries().isEmpty());
    }

    @Test
    void idDateWithJournalsRunsTheMaxCreatedDateSchemaAndReturnsNullOnEmpty() throws Exception {
        this.resultsToServe.add(entryRow());
        ProvisioningEntryData found = newService().retrieveExistingProvisioningIdDateWithJournals();
        assertEquals(ID_DATE_SCHEMA, this.sqls.get(0), "the mapper schema IS the statement");
        assertEquals(11L, found.getId());
        assertTrue(found.getJournalEntry());

        this.resultsToServe.add(emptyRs());
        assertNull(newService().retrieveExistingProvisioningIdDateWithJournals(), "empty result maps to null, never throws");
    }
}
