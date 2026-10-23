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
package org.apache.fineract.portfolio.charge.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.accounting.common.AccountingDropdownReadPlatformService;
import org.apache.fineract.accounting.glaccount.data.GLAccountData;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainServiceJpa;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.entityaccess.domain.FineractEntityType;
import org.apache.fineract.infrastructure.entityaccess.service.FineractEntityAccessUtil;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.service.CurrencyReadPlatformService;
import org.apache.fineract.portfolio.charge.data.ChargeData;
import org.apache.fineract.portfolio.charge.exception.ChargeNotFoundException;
import org.apache.fineract.portfolio.common.service.DropdownReadPlatformService;
import org.apache.fineract.portfolio.tax.data.TaxGroupData;
import org.apache.fineract.portfolio.tax.service.TaxReadPlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * S4a characterization suite (MC 11183): pins the CURRENT SQL-building and row-mapping behavior of the
 * 17 query sites and the ChargeMapper of {@link ChargeReadPlatformServiceImpl} at pin 355287c8 WITHOUT a
 * live database: the JdbcTemplate / NamedParameterJdbcTemplate collaborators are mocked, the SQL string
 * and RowMapper arguments are captured, and the captured mapper is driven against a mock ResultSet.
 * The database itself (schema, join truth, Spring cache proxy on retrieveAllCharges, and real query
 * execution) is the named GAP — see the S4 evidence file "DB-gap" rows. Suspect pins are labeled; rows in
 * research/04 "S4 suspect map".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChargeReadPlatformServiceImplCharacterizationTest {

    @Mock
    private CurrencyReadPlatformService currencyReadPlatformService;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DropdownReadPlatformService dropdownReadPlatformService;
    @Mock
    private FineractEntityAccessUtil fineractEntityAccessUtil;
    @Mock
    private AccountingDropdownReadPlatformService accountingDropdownReadPlatformService;
    @Mock
    private TaxReadPlatformService taxReadPlatformService;
    @Mock
    private ConfigurationDomainServiceJpa configurationDomainServiceJpa;
    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    private final ChargeDropdownReadPlatformService chargeDropdownReadPlatformService = new ChargeDropdownReadPlatformServiceImpl();

    private ChargeReadPlatformServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChargeReadPlatformServiceImpl(currencyReadPlatformService, chargeDropdownReadPlatformService, jdbcTemplate,
                dropdownReadPlatformService, fineractEntityAccessUtil, accountingDropdownReadPlatformService, taxReadPlatformService,
                configurationDomainServiceJpa, namedParameterJdbcTemplate);
        // office-scoping off unless a pin says otherwise
        when(fineractEntityAccessUtil.getSQLWhereClauseForProductIDsForUserOffice_ifGlobalConfigEnabled(FineractEntityType.CHARGE))
                .thenReturn("");
        // ChargeMapper reaches DateUtils.safeMonthDay -> business-date context (EnricherTest idiom)
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2024, 6, 15))));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String captureQuery(Runnable call) {
        AtomicReference<String> sql = new AtomicReference<>();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return List.of();
        });
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return List.of();
        });
        call.run();
        return sql.get();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RowMapper<ChargeData> capturedMapper() {
        AtomicReference<RowMapper<ChargeData>> mapper = new AtomicReference<>();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            mapper.set(invocation.getArgument(1));
            return List.of();
        });
        service.retrieveAllCharges();
        return mapper.get();
    }

    // ---- SQL building: m_charge core schema and where-shape ----

    @Test
    void retrieveAllChargesSendsTheSoftDeleteFilteredNameOrderedChargeSchema() {
        String sql = captureQuery(() -> service.retrieveAllCharges());
        assertTrue(sql.startsWith("select c.id as id, c.name as name, c.amount as amount, c.currency_code as currencyCode,"), sql);
        assertTrue(sql.contains("from m_charge c join m_organisation_currency oc on c.currency_code = oc.code"), sql);
        assertTrue(sql.contains("LEFT JOIN acc_gl_account acc on acc.id = c.income_or_liability_account_id"), sql);
        assertTrue(sql.contains("LEFT JOIN m_tax_group tg on tg.id = c.tax_group_id"), sql);
        assertTrue(sql.contains("LEFT JOIN m_payment_type pt on pt.id = c.payment_type_id"), sql);
        assertTrue(sql.contains("where c.is_deleted=false "), sql);
        assertTrue(sql.endsWith(" order by c.name "), sql);
        assertFalse(sql.contains("c.id in ("), "office-scoping in-clause must be absent when entity-access returns empty");
    }

    @Test
    void officeScopedInClauseIsWrappedIntoTheWhereClauseVerbatim() {
        // CHARACTERIZED (suspect — S4 suspect map S-R1): the entity-access fragment is string-concatenated
        // into the WHERE clause with no parameter binding — it is trusted SQL authored elsewhere (office
        // scoping), and the charge side re-embeds it; pin the exact wrapping shape (" and c.id in ( X ) ")
        when(fineractEntityAccessUtil.getSQLWhereClauseForProductIDsForUserOffice_ifGlobalConfigEnabled(FineractEntityType.CHARGE))
                .thenReturn("select 1 from m_office");

        String sql = captureQuery(() -> service.retrieveAllCharges());
        assertTrue(sql.contains(" and c.id in ( select 1 from m_office ) "), sql);
    }

    @Test
    void retrieveAllChargesForCurrencyAppendsTheCurrencyPredicateWithOnePositionalArgument() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        AtomicReference<String> sql = new AtomicReference<>();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), args.capture())).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return List.of();
        });

        service.retrieveAllChargesForCurrency("EUR");

        assertTrue(sql.get().contains("where c.is_deleted=false and c.currency_code= ? "), sql.get());
        assertEquals("EUR", args.getValue()[0]);
    }

    @Test
    void retrieveChargeMapsEmptyResultToChargeNotFoundAndKeepsTheTrailingSemicolon() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        ChargeNotFoundException ex = assertThrows(ChargeNotFoundException.class, () -> service.retrieveCharge(9L));
        assertEquals("error.msg.charge.id.invalid", ex.getGlobalisationMessageCode());

        verify(jdbcTemplate).queryForObject(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertTrue(sql.getValue().contains("where c.id = ? and c.is_deleted=false "), sql.getValue());
        assertTrue(sql.getValue().endsWith(";"), "retrieveCharge appends a bare ';' — pinned verbatim");
    }

    @Test
    void productChargeQueriesJoinTheLinkingTablesAndFilterActiveOnly() {
        String loanProduct = captureQuery(() -> service.retrieveLoanProductCharges(11L));
        assertTrue(loanProduct.contains("join m_product_loan_charge plc on plc.charge_id = c.id"), loanProduct);
        assertTrue(loanProduct.contains("c.is_deleted=false and c.is_active=true and plc.product_loan_id=? "), loanProduct);

        String loanProductTimed = captureQuery(() -> service.retrieveLoanProductCharges(11L,
                org.apache.fineract.portfolio.charge.domain.ChargeTimeType.SPECIFIED_DUE_DATE));
        assertTrue(loanProductTimed.contains("and c.charge_time_enum=? "), loanProductTimed);

        String savingsProduct = captureQuery(() -> service.retrieveSavingsProductCharges(12L));
        assertTrue(savingsProduct.contains("join m_savings_product_charge spc on spc.charge_id = c.id"), savingsProduct);
        assertTrue(savingsProduct.contains("spc.savings_product_id=? "), savingsProduct);

        String shareProduct = captureQuery(() -> service.retrieveShareProductCharges(13L));
        assertTrue(shareProduct.contains("join m_share_product_charge mspc on mspc.charge_id = c.id"), shareProduct);
        assertTrue(shareProduct.contains("mspc.product_id=? "), shareProduct);
    }

    @Test
    void feeAndPenaltySelectorsDifferByPenaltyAndAppliesToPredicate() {
        String fees = captureQuery(() -> service.retrieveLoanApplicableFees());
        assertTrue(fees.contains("c.is_penalty=false and c.charge_applies_to_enum=? "), fees);

        String penalties = captureQuery(() -> service.retrieveLoanApplicablePenalties());
        assertTrue(penalties.contains("c.is_penalty=true and c.charge_applies_to_enum=? "), penalties);

        String savingsFeesAll = captureQuery(() -> service.retrieveSavingsProductApplicableCharges(false));
        // "c.is_penalty as penalty" is part of the shared SELECT schema — the pin checks the WHERE clause only
        assertFalse(savingsFeesAll.contains("and c.is_penalty"), savingsFeesAll);
        String savingsFeesOnly = captureQuery(() -> service.retrieveSavingsProductApplicableCharges(true));
        assertTrue(savingsFeesOnly.contains("c.is_penalty=false"), savingsFeesOnly);

        captureQuery(() -> service.retrieveAllChargesApplicableToClients());
        captureQuery(() -> service.retrieveSharesApplicableCharges());
    }

    @Test
    void namedParameterQueriesBindAccountAndProductIdsButInlineTheExcludedChargeTimes() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        when(namedParameterJdbcTemplate.query(anyString(), anyMap(), any(RowMapper.class))).thenAnswer(invocation -> List.of());

        service.retrieveLoanAccountApplicableCharges(21L,
                new org.apache.fineract.portfolio.charge.domain.ChargeTimeType[] {
                        org.apache.fineract.portfolio.charge.domain.ChargeTimeType.SPECIFIED_DUE_DATE,
                        org.apache.fineract.portfolio.charge.domain.ChargeTimeType.WITHDRAWAL_FEE });

        verify(namedParameterJdbcTemplate).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertTrue(sql.getValue().contains("join m_loan la on la.currency_code = c.currency_code"), sql.getValue());
        assertTrue(sql.getValue().contains("where la.id=:loanId"), sql.getValue());
        // CHARACTERIZED (suspect — S4 suspect map S-R2): the excluded charge times are INLINED as raw
        // integers ("not in(2,5)"), not named parameters — enum-sourced so not an injection hole, but a
        // different binding discipline than the surrounding :named params in the same statement.
        assertTrue(sql.getValue().contains("c.charge_time_enum not in(2,5)"), sql.getValue());
        assertEquals(21L, params.getValue().get("loanId"));
        assertEquals(1, params.getValue().get("chargeAppliesTo"));

        service.retrieveLoanAccountApplicableCharges(21L, null);
        verify(namedParameterJdbcTemplate,
                org.mockito.Mockito.times(2)).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertFalse(sql.getValue().contains("not in"), "null exclusions add no clause");
    }

    @Test
    void workingCapitalAccountQueryFiltersByTheNamedChargeTimeSetParameter() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        when(namedParameterJdbcTemplate.query(anyString(), anyMap(), any(RowMapper.class))).thenAnswer(invocation -> List.of());

        service.retrieveWorkingCapitalLoanAccountApplicableCharges(31L);

        verify(namedParameterJdbcTemplate).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertTrue(sql.getValue().contains("join m_wc_loan la on la.currency_code = c.currency_code"), sql.getValue());
        assertTrue(sql.getValue().contains("c.charge_time_enum in (:chargeTimeTypes)"), sql.getValue());
        assertEquals(List.of(2), params.getValue().get("chargeTimeTypes"), "WCL account set = SPECIFIED_DUE_DATE only");
        assertEquals(5, params.getValue().get("chargeAppliesTo"));
    }

    // ---- DTO template paths — these NEVER touch JDBC ----

    @Test
    void retrieveNewChargeDetailsAggregatesDropdownsConfigAndTaxOptionsIntoTheTemplateWithoutJdbc() {
        List<EnumOptionData> periodOptions = List.of(new EnumOptionData(2L, "periodFrequencyType.months", "Monthly"));
        Map<String, List<GLAccountData>> accountMapping = new HashMap<>();
        accountMapping.put("LOAN_ADMINISTRATOR", List.of(new GLAccountData().setId(1L).setName("Fee income").setGlCode("4000")));
        List<TaxGroupData> taxGroups = List.of(TaxGroupData.lookup(2L, "VAT"));

        when(currencyReadPlatformService.retrieveAllowedCurrencies()).thenReturn(List.of());
        when(dropdownReadPlatformService.retrievePeriodFrequencyTypeOptions()).thenReturn(periodOptions);
        when(accountingDropdownReadPlatformService.retrieveAccountMappingOptionsForCharges()).thenReturn(accountMapping);
        when(accountingDropdownReadPlatformService.retrieveExpenseAccountOptions()).thenReturn(List.of());
        when(accountingDropdownReadPlatformService.retrieveAssetAccountOptions()).thenReturn(List.of());
        when(taxReadPlatformService.retrieveTaxGroupsForLookUp()).thenReturn(taxGroups);
        when(configurationDomainServiceJpa.getAccountMappingForCharge()).thenReturn("cfg");

        ChargeData template = service.retrieveNewChargeDetails();

        assertSame(periodOptions, template.getFeeFrequencyOptions());
        assertSame(accountMapping, template.getIncomeOrLiabilityAccountOptions());
        assertSame(taxGroups, template.getTaxGroupOptions());
        assertEquals("cfg", template.getAccountMappingForChargeConfig());
        assertEquals(List.of(0L, 1L), template.getChargePaymetModeOptions().stream().map(EnumOptionData::getId).toList());
        verifyNoInteractions(jdbcTemplate, namedParameterJdbcTemplate);
    }

    @Test
    void retrieveNewChargeDetailsForWorkingCapitalNarrowsEveryOptionListAndSkipsTheSharedDropdowns() {
        when(currencyReadPlatformService.retrieveAllowedCurrencies()).thenReturn(List.of());

        ChargeData wclTemplate = service.retrieveNewChargeDetails(5L, 2L);

        assertEquals(List.of(1L, 2L), wclTemplate.getChargeTimeTypeOptions().stream().map(EnumOptionData::getId).toList());
        assertEquals(List.of(1L), wclTemplate.getChargeCalculationTypeOptions().stream().map(EnumOptionData::getId).toList(),
                "SPECIFIED_DUE_DATE narrows calculation types to FLAT");
        assertEquals(List.of(0L), wclTemplate.getChargePaymetModeOptions().stream().map(EnumOptionData::getId).toList());
        // the narrow branch never asks the fee-frequency/GL/tax/config providers at all:
        verifyNoInteractions(dropdownReadPlatformService, accountingDropdownReadPlatformService, taxReadPlatformService,
                configurationDomainServiceJpa);
        verifyNoInteractions(jdbcTemplate, namedParameterJdbcTemplate);
    }

    // ---- ChargeMapper row mapping, driven off the captured RowMapper ----

    /**
     * Dynamic-proxy ResultSet: consistent label and 1-based index reads for the getters ChargeMapper uses
     * (getString/getInt/getLong/getBoolean/getBigDecimal/getObject + findColumn/wasNull). Spring's
     * JdbcUtils dispatch (via JdbcSupport) reaches the same column either way — this fake keeps them in
     * lockstep so the row mapping is pinned without a database.
     */
    private static ResultSet fakeRow(java.util.LinkedHashMap<String, Object> cols) {
        Map<String, Integer> index = new java.util.LinkedHashMap<>();
        int i = 0;
        for (String key : cols.keySet()) {
            index.put(key, ++i);
        }
        Map<Integer, String> byIndex = new HashMap<>();
        index.forEach((label, col) -> byIndex.put(col, label));
        final boolean[] lastNull = { false };
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if (args == null) {
                // zero-arg call — only wasNull() matters for Spring's JdbcUtils null-detection
                if ("wasNull".equals(name)) {
                    return lastNull[0];
                }
                Class<?> rt = method.getReturnType();
                if (rt == int.class) {
                    return 0;
                }
                if (rt == long.class) {
                    return 0L;
                }
                if (rt == boolean.class) {
                    return false;
                }
                return null;
            }
            java.util.function.Function<Object, Object> labelValue = arg -> {
                String label = arg instanceof Integer ? byIndex.get(arg) : (String) arg;
                return label == null ? null : cols.get(label);
            };
            switch (name) {
                case "findColumn":
                    return index.computeIfAbsent((String) args[0], k -> index.size() + 1);
                case "wasNull":
                    return lastNull[0];
                case "getString": {
                    Object v = labelValue.apply(args[0]);
                    lastNull[0] = v == null;
                    return v == null ? null : v.toString();
                }
                case "getInt": {
                    Object v = labelValue.apply(args[0]);
                    lastNull[0] = v == null;
                    return v instanceof Number n ? n.intValue() : 0;
                }
                case "getLong": {
                    Object v = labelValue.apply(args[0]);
                    lastNull[0] = v == null;
                    return v instanceof Number n ? n.longValue() : 0L;
                }
                case "getBoolean": {
                    Object v = labelValue.apply(args[0]);
                    lastNull[0] = v == null;
                    return v instanceof Boolean b ? b : (v instanceof Number n && n.intValue() != 0);
                }
                case "getBigDecimal": {
                    Object v = labelValue.apply(args[0]);
                    lastNull[0] = v == null;
                    return v == null ? null : (v instanceof BigDecimal bd ? bd : new BigDecimal(v.toString()));
                }
                case "getObject": {
                    Object v = labelValue.apply(args[0]);
                    lastNull[0] = v == null;
                    return v;
                }
                case "toString":
                    return "FakeResultSet";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default: {
                    Class<?> rt = method.getReturnType();
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    if (rt == boolean.class) {
                        return false;
                    }
                    return null;
                }
            }
        };
        return (ResultSet) java.lang.reflect.Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class[] { ResultSet.class },
                handler);
    }

    @Test
    void chargeMapperMapsOneRowIntoChargeDataWithEnumOptionsGuardsAndPrimitiveCollapse() throws Exception {
        // pin: label/index reads, the three guarded optional associations (GL account / tax group / payment
        // type), MonthDay assembly — AND the primitive NULL-collapse: free-withdrawal ints are read with
        // getInt, so a NULL column arrives as 0 (a legal-looking value) instead of null.
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", 7L);
        row.put("name", "Fee A");
        row.put("amount", new BigDecimal("10.00"));
        row.put("currencyCode", "USD");
        row.put("currencyName", "US Dollar");
        row.put("currencyNameCode", "USD.code");
        row.put("currencyDisplaySymbol", "$");
        row.put("currencyDecimalPlaces", 2);
        row.put("inMultiplesOf", null);
        row.put("chargeAppliesTo", 2);
        row.put("chargeTime", 5);
        row.put("chargeCalculation", 1);
        row.put("chargePaymentMode", 0);
        row.put("penalty", false);
        row.put("active", true);
        row.put("feeInterval", null);
        row.put("feeFrequency", 2);
        row.put("feeOnMonth", 1);
        row.put("feeOnDay", 15);
        row.put("minCap", new BigDecimal("1.50"));
        row.put("maxCap", new BigDecimal("9.50"));
        row.put("glAccountId", 5L);
        row.put("glAccountName", "Fee income");
        row.put("glCode", "4000");
        row.put("taxGroupId", null);
        row.put("taxGroupName", null);
        row.put("isFreeWithdrawal", true);
        row.put("freeWithdrawalChargeFrequency", null);
        row.put("restartFrequency", null);
        row.put("restartFrequencyEnum", null);
        row.put("isPaymentType", false);
        row.put("paymentTypeId", null);
        row.put("paymentTypeName", null);

        ChargeData data = capturedMapper().mapRow(fakeRow(row), 0);

        assertEquals(7L, data.getId());
        assertEquals("Fee A", data.getName());
        assertEquals(new BigDecimal("10.00"), data.getAmount());
        assertEquals("USD", data.getCurrency().getCode());
        assertEquals("US Dollar", data.getCurrency().getName());
        assertEquals(Integer.valueOf(2), data.getCurrency().getDecimalPlaces());
        assertEquals("Savings", data.getChargeAppliesTo().getValue());
        assertEquals("Withdrawal Fee", data.getChargeTimeType().getValue());
        assertEquals("Flat", data.getChargeCalculationType().getValue());
        assertEquals("Regular", data.getChargePaymentMode().getValue());
        assertEquals(MonthDay.of(1, 15), data.getFeeOnMonthDay());
        assertEquals(Long.valueOf(2L), data.getFeeFrequency().getId());
        assertEquals(5L, data.getIncomeOrLiabilityAccount().getId());
        assertEquals("4000", data.getIncomeOrLiabilityAccount().getGlCode());
        assertNull(data.getTaxGroup());
        assertNull(data.getPaymentTypeOptions());
        assertTrue(data.isFreeWithdrawal());
        assertEquals(Integer.valueOf(0), data.getFreeWithdrawalChargeFrequency(),
                "NULL frequency columns arrive as primitive 0 via getInt — never null on the DTO (S-R2)");
        assertEquals(Integer.valueOf(0), data.getRestartFrequencyEnum());
        assertNull(data.getFeeInterval());
    }

    @Test
    void chargeMapperOmitsTheGuardedAssociationsOnlyWhenTheirIdsAreNull() throws Exception {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", 9L);
        row.put("currencyDecimalPlaces", 2);
        row.put("glAccountId", null);
        row.put("taxGroupId", 3L);
        row.put("taxGroupName", "VAT");
        row.put("paymentTypeId", 8L);
        row.put("paymentTypeName", "cash");
        // CHARACTERIZED (suspect — S4 suspect map S-R3): CurrencyData construction UNBOXES
        // currencyDecimalPlaces — a row with a NULL decimal-places column NPEs the whole mapper. The row
        // here carries 2 to reach the association guards; the NULL-decimal NPE is pinned in
        // chargeMapperFailsOnNullCurrencyDecimalPlaces.

        ChargeData data = capturedMapper().mapRow(fakeRow(row), 0);

        assertNull(data.getIncomeOrLiabilityAccount());
        assertEquals(3L, data.getTaxGroup().getId());
        assertEquals("VAT", data.getTaxGroup().getName());
        assertEquals(8L, data.getPaymentTypeOptions().getId());
        assertEquals("cash", data.getPaymentTypeOptions().getName());
    }

    @Test
    void chargeMapperFailsOnNullCurrencyDecimalPlaces() throws Exception {
        // CHARACTERIZED (suspect — S4 suspect map S-R3): JdbcSupport.getInteger can return null (it does
        // for a NULL decimal_places column), and ChargeMapper hands it straight to CurrencyData's
        // unboxing constructor — mapping such a row throws instead of producing a DTO with a null digit
        // count.
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", 9L);
        row.put("currencyDecimalPlaces", null);

        assertThrows(NullPointerException.class, () -> capturedMapper().mapRow(fakeRow(row), 0));
    }

    @Test
    void currencyOnlyTemplateKeepsCurrencyListIdentity() {
        CurrencyData usd = new CurrencyData("USD", null, 2, 0, "$", "USD.code");
        when(currencyReadPlatformService.retrieveAllowedCurrencies()).thenReturn(List.of(usd));
        ChargeData template = service.retrieveNewChargeDetails();
        assertSame(template.getCurrencyOptions().stream().findFirst().orElseThrow(), usd);
        CurrencyData eu = new CurrencyData("EUR", null, 2, 0, "€", "EUR.code");
        when(currencyReadPlatformService.retrieveAllowedCurrencies()).thenReturn(List.of(usd, eu));
        assertEquals(2, service.retrieveNewChargeDetails(5L, 1L).getCurrencyOptions().size());
    }
}
