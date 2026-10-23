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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepositoryWrapper;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.entityaccess.domain.FineractEntityAccessType;
import org.apache.fineract.infrastructure.entityaccess.service.FineractEntityAccessUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeRepository;
import org.apache.fineract.portfolio.charge.exception.ChargeCannotBeDeletedException;
import org.apache.fineract.portfolio.charge.exception.ChargeCannotBeUpdatedException;
import org.apache.fineract.portfolio.charge.exception.ChargeNotFoundException;
import org.apache.fineract.portfolio.charge.serialization.ChargeDefinitionCommandFromApiJsonDeserializer;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentType;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentTypeRepository;
import org.apache.fineract.portfolio.tax.domain.TaxGroupRepositoryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S4a characterization suite (MC 11183): pins the CURRENT behavior of
 * {@link ChargeWritePlatformServiceJpaRepositoryImpl} at pin 355287c8 — validation delegation, the four
 * usage-guard EXISTS SQL strings (captured from the mocked JdbcTemplate, executed WITHOUT a database),
 * the guard ORDER, the data-integrity error mapping, and the soft delete. Suspect pins are labeled; rows
 * in research/04 "S4 suspect map".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChargeWritePlatformServiceCharacterizationTest {

    private static final Long CHARGE_ID = 42L;

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private ChargeRepository chargeRepository;
    @Mock
    private LoanProductRepository loanProductRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private FineractEntityAccessUtil fineractEntityAccessUtil;
    @Mock
    private GLAccountRepositoryWrapper glAccountRepository;
    @Mock
    private TaxGroupRepositoryWrapper taxGroupRepository;
    @Mock
    private PaymentTypeRepository paymentTypeRepository;

    private final FromJsonHelper fromJsonHelper = new FromJsonHelper();

    private ChargeWritePlatformServiceJpaRepositoryImpl service;

    @BeforeEach
    void setUp() {
        service = new ChargeWritePlatformServiceJpaRepositoryImpl(context,
                new ChargeDefinitionCommandFromApiJsonDeserializer(fromJsonHelper), chargeRepository, loanProductRepository, jdbcTemplate,
                fineractEntityAccessUtil, glAccountRepository, taxGroupRepository, paymentTypeRepository);
    }

    private JsonCommand command(String json) {
        return JsonCommand.from(json, JsonParser.parseString(json), fromJsonHelper, "charge", null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private Charge existingCharge(String body) {
        Charge charge = Charge.fromJson(command("{" + body + "}"), null, null, null);
        charge.setId(CHARGE_ID);
        return charge;
    }

    private void stubGuardQueries(String loanProductsAnswer, String savingsProductsAnswer) {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("m_product_loan_charge")) {
                return loanProductsAnswer;
            }
            return savingsProductsAnswer;
        });
    }

    // ---- create ----

    @Test
    void createPersistsTheChargeAndRegistersOfficeScopeReturningTheNewId() {
        when(chargeRepository.saveAndFlush(any(Charge.class))).thenAnswer(invocation -> {
            Charge charge = invocation.getArgument(0);
            charge.setId(99L);
            return charge;
        });

        var result = service.createCharge(command("{\"chargeAppliesTo\":1,\"chargeTimeType\":1,\"chargeCalculationType\":1,"
                + "\"chargePaymentMode\":0,\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10,\"locale\":\"en\"}"));

        assertEquals(99L, result.getResourceId());
        verify(fineractEntityAccessUtil).checkConfigurationAndAddProductResrictionsForUserOffice(
                FineractEntityAccessType.OFFICE_ACCESS_TO_CHARGES, 99L);
        verifyNoInteractions(glAccountRepository, taxGroupRepository, paymentTypeRepository);
    }

    @Test
    void createWithEnablePaymentTypeTrueAndNoPaymentTypeIdSilentlyDropsTheFlag() {
        // CHARACTERIZED (suspect — S4 suspect map S-W1): validation passes (null-skip pin S-C2 in the
        // module suite), the service skips the payment-type lookup for a null paymentTypeId — AND
        // Charge.fromJson only stores enablePaymentType when a NON-NULL paymentType is present (line:
        // "if (enablePaymentType && paymentType != null)"), so the flag is silently DROPPED: a charge
        // created with enablePaymentType=true and no paymentTypeId persists with the flag FALSE.
        when(chargeRepository.saveAndFlush(any(Charge.class))).thenAnswer(invocation -> {
            Charge charge = invocation.getArgument(0);
            charge.setId(98L);
            return charge;
        });

        service.createCharge(command("{\"chargeAppliesTo\":1,\"chargeTimeType\":1,\"chargeCalculationType\":1,"
                + "\"chargePaymentMode\":0,\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10,\"enablePaymentType\":true,\"locale\":\"en\"}"));

        verifyNoInteractions(paymentTypeRepository);
        ArgumentCaptor<Charge> saved = ArgumentCaptor.forClass(Charge.class);
        verify(chargeRepository).saveAndFlush(saved.capture());
        assertFalse(saved.getValue().isEnablePaymentType(),
                "enablePaymentType=true without a paymentTypeId is stored as FALSE");
    }

    @Test
    void createDuplicateNameIsMappedFromTheDataIntegrityMessageBySubstringMatch() {
        // CHARACTERIZED (suspect — S4 suspect map S-W2): handleDataIntegrityIssues classifies by the
        // substring "name" ANYWHERE in the root-cause message — a violation whose message merely mentions
        // "name" (here an unrelated NOT-NULL failure on a column called office_name) is reported as a
        // duplicate charge name. Pin records the misclassification as-is.
        when(chargeRepository.saveAndFlush(any(Charge.class)))
                .thenThrow(new DataIntegrityViolationException("null value in column \"office_name\" violates not-null constraint"));

        PlatformDataIntegrityException ex = assertThrows(PlatformDataIntegrityException.class,
                () -> service.createCharge(command("{\"chargeAppliesTo\":1,\"chargeTimeType\":1,\"chargeCalculationType\":1,"
                        + "\"chargePaymentMode\":0,\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10,\"locale\":\"en\"}")));
        assertEquals("error.msg.charge.duplicate.name", ex.getGlobalisationMessageCode());
    }

    // ---- update: usage guards and their SQL ----

    @Test
    void deactivationChecksBothProductUsageGuardsEvenWhenTheFirstAlreadyBlocks() {
        Charge activeCharge = existingCharge("\"name\": \"u1\", \"amount\": 10, \"currencyCode\": \"USD\","
                + " \"chargeAppliesTo\": 1, \"chargeTimeType\": 1, \"chargeCalculationType\": 1, \"chargePaymentMode\": 0,"
                + " \"penalty\": false, \"active\": true, \"locale\": \"en\"");
        when(chargeRepository.findById(CHARGE_ID)).thenReturn(Optional.of(activeCharge));
        stubGuardQueries("true", "true");

        assertThrows(ChargeCannotBeUpdatedException.class,
                () -> service.updateCharge(CHARGE_ID, command("{\"active\": false, \"locale\": \"en\"}")));

        ArgumentCaptor<String> sqls = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).queryForObject(sqls.capture(), eq(String.class), any(Object[].class));
        List<String> sent = sqls.getAllValues();
        // both guards were evaluated — the booleans are assigned before the || is tested
        assertTrue(sent.get(0).contains("from m_product_loan_charge lc where lc.charge_id = ?"), sent.get(0));
        assertTrue(sent.get(1).contains("from m_savings_product_charge sc where sc.charge_id = ?"), sent.get(1));
        // verbatim EXACT SQL shape (byte-identity anchor for the S4 peel):
        assertEquals("select (CASE WHEN exists (select 1 from m_product_loan_charge lc where lc.charge_id = ?)"
                + " THEN 'true' ELSE 'false' END)", sent.get(0).trim());
        verify(chargeRepository, never()).save(any(Charge.class));
    }

    @Test
    void feeChangeOnLoanChargeRunsTheLoanProductGuardOnly() {
        Charge charge = existingCharge("\"name\": \"u2\", \"amount\": 10, \"currencyCode\": \"USD\","
                + " \"chargeAppliesTo\": 1, \"chargeTimeType\": 1, \"chargeCalculationType\": 1, \"chargePaymentMode\": 0,"
                + " \"penalty\": false, \"active\": true, \"locale\": \"en\"");
        when(chargeRepository.findById(CHARGE_ID)).thenReturn(Optional.of(charge));
        stubGuardQueries("false", "false");

        var result = service.updateCharge(CHARGE_ID, command("{\"feeFrequency\": 2, \"feeInterval\": 3, \"locale\": \"en\"}"));

        assertEquals(3, result.getChanges().get("feeInterval"));
        ArgumentCaptor<String> sqls = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).queryForObject(sqls.capture(), eq(String.class), any(Object[].class));
        assertTrue(sqls.getValue().contains("m_product_loan_charge"), sqls.getValue());
    }

    @Test
    void reactivationOfAUsedChargeRunsNoUsageGuardAtAll() {
        // CHARACTERIZED (suspect — S4 suspect map S-W3): the active-branch guard tests the state AFTER
        // update() has already been applied — `if (changes.containsKey("active")) { if (!chargeForUpdate
        // .isActive()) ... }` — so an INCOMING reactivation of a charge used by loan AND savings products
        // runs NO guard at all, and because the fee guard sits in the else-if it is skipped too: the whole
        // update, including feeInterval, is applied with zero usage probes.
        Charge inactive = existingCharge("\"name\": \"u3\", \"amount\": 10, \"currencyCode\": \"USD\","
                + " \"chargeAppliesTo\": 1, \"chargeTimeType\": 1, \"chargeCalculationType\": 1, \"chargePaymentMode\": 0,"
                + " \"penalty\": false, \"active\": false, \"locale\": \"en\"");
        when(chargeRepository.findById(CHARGE_ID)).thenReturn(Optional.of(inactive));
        stubGuardQueries("true", "true");

        var result = service.updateCharge(CHARGE_ID, command("{\"active\": true, \"feeInterval\": 9, \"locale\": \"en\"}"));

        assertEquals(9, result.getChanges().get("feeInterval"));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void updateOfUnknownChargeThrowsNotFoundBeforeAnyGuard() {
        when(chargeRepository.findById(CHARGE_ID)).thenReturn(Optional.empty());
        assertThrows(ChargeNotFoundException.class,
                () -> service.updateCharge(CHARGE_ID, command("{\"name\": \"x\", \"locale\": \"en\"}")));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void updatePaymentTypeIdNullIsReportedWhileThePaymentTypeStaysAttached() {
        // CHARACTERIZED (suspect — S4 suspect map S-W4, service side of the entity pin S-C13): the change
        // map carries paymentTypeId=null but the service only applies NON-NULL values — the type stays
        // attached and the repository is never consulted: a reported change that is never applied.
        PaymentType paymentType = new PaymentType();
        paymentType.setId(9L);
        paymentType.setName("cash");
        Charge charge = Charge.fromJson(command("{\"name\": \"u4\", \"amount\": 10, \"currencyCode\": \"USD\","
                + " \"chargeAppliesTo\": 1, \"chargeTimeType\": 1, \"chargeCalculationType\": 1, \"chargePaymentMode\": 0,"
                + " \"penalty\": false, \"active\": true, \"enablePaymentType\": true, \"locale\": \"en\"}"), null, null, paymentType);
        charge.setId(CHARGE_ID);
        when(chargeRepository.findById(CHARGE_ID)).thenReturn(Optional.of(charge));

        // the stored row carries a payment type — Charge.fromJson only attaches one when the JSON names a
        // non-null paymentTypeId, so wire it on the entity the way a loaded row would have it
        charge.setPaymentType(paymentType);
        var result = service.updateCharge(CHARGE_ID, command("{\"paymentTypeId\": null, \"locale\": \"en\"}"));

        assertTrue(result.getChanges().containsKey("paymentTypeId"));
        verifyNoInteractions(paymentTypeRepository);
        assertEquals(9L, charge.toData().getPaymentTypeOptions().getId());
    }

    // ---- delete ----

    @Test
    void deleteOfAlreadySoftDeletedChargeIsNotFoundBeforeAnyUsageProbe() {
        Charge deleted = existingCharge("\"name\": \"d1\", \"amount\": 10, \"currencyCode\": \"USD\","
                + " \"chargeAppliesTo\": 1, \"chargeTimeType\": 1, \"chargeCalculationType\": 1, \"chargePaymentMode\": 0,"
                + " \"penalty\": false, \"active\": true, \"locale\": \"en\"");
        deleted.delete();
        when(chargeRepository.findById(CHARGE_ID)).thenReturn(Optional.of(deleted));

        assertThrows(ChargeNotFoundException.class, () -> service.deleteCharge(CHARGE_ID));
        verifyNoInteractions(jdbcTemplate, loanProductRepository);
    }

    @Test
    void deleteIsBlockedWhenAnyUsageExistsAndTheFourGuardsRunInThisExactOrder() {
        Charge charge = existingCharge("\"name\": \"d2\", \"amount\": 10, \"currencyCode\": \"USD\","
                + " \"chargeAppliesTo\": 1, \"chargeTimeType\": 1, \"chargeCalculationType\": 1, \"chargePaymentMode\": 0,"
                + " \"penalty\": false, \"active\": true, \"locale\": \"en\"");
        when(chargeRepository.findById(CHARGE_ID)).thenReturn(Optional.of(charge));
        when(loanProductRepository.retrieveLoanProductsByChargeId(CHARGE_ID)).thenReturn(List.of(new LoanProduct()));
        when(chargeRepository.isAnyWorkingCapitalLoansAssociateWithThisCharge(CHARGE_ID)).thenReturn(Optional.empty());

        assertThrows(ChargeCannotBeDeletedException.class, () -> service.deleteCharge(CHARGE_ID));

        // loan-account guard runs (SQL pinned verbatim), savings-account guard runs, working-capital
        // repository probe runs — order: loanProduct list first, then jdbc loan, jdbc savings, repo WCL
        ArgumentCaptor<String> sqls = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).queryForObject(sqls.capture(), eq(String.class), any(Object[].class));
        assertTrue(sqls.getAllValues().get(0).contains("from m_loan_charge lc where lc.charge_id = ? and lc.is_active = true"),
                sqls.getAllValues().get(0));
        assertTrue(sqls.getAllValues().get(1).contains("from m_savings_account_charge sc where sc.charge_id = ? and sc.is_active = true"),
                sqls.getAllValues().get(1));
        verify(chargeRepository).isAnyWorkingCapitalLoansAssociateWithThisCharge(CHARGE_ID);
        verify(chargeRepository, never()).save(any(Charge.class));
    }

    @Test
    void cleanDeleteSoftDeletesAndDecoratesTheNameWithTheId() {
        Charge charge = existingCharge("\"name\": \"d3\", \"amount\": 10, \"currencyCode\": \"USD\","
                + " \"chargeAppliesTo\": 1, \"chargeTimeType\": 1, \"chargeCalculationType\": 1, \"chargePaymentMode\": 0,"
                + " \"penalty\": false, \"active\": true, \"locale\": \"en\"");
        when(chargeRepository.findById(CHARGE_ID)).thenReturn(Optional.of(charge));
        when(loanProductRepository.retrieveLoanProductsByChargeId(CHARGE_ID)).thenReturn(List.of());
        when(chargeRepository.isAnyWorkingCapitalLoansAssociateWithThisCharge(CHARGE_ID)).thenReturn(Optional.empty());
        stubGuardQueries("false", "false");

        var result = service.deleteCharge(CHARGE_ID);

        assertEquals(CHARGE_ID, result.getResourceId());
        ArgumentCaptor<Charge> saved = ArgumentCaptor.forClass(Charge.class);
        verify(chargeRepository).save(saved.capture());
        assertTrue(saved.getValue().isDeleted());
        assertEquals("42_d3", saved.getValue().getName());
    }

    @Test
    void workingCapitalUsageRepositoryProbeAlsoBlocksTheDelete() {
        Charge charge = existingCharge("\"name\": \"d4\", \"amount\": 10, \"currencyCode\": \"USD\","
                + " \"chargeAppliesTo\": 5, \"chargeTimeType\": 1, \"chargeCalculationType\": 1, \"chargePaymentMode\": 0,"
                + " \"penalty\": false, \"active\": true, \"locale\": \"en\"");
        when(chargeRepository.findById(CHARGE_ID)).thenReturn(Optional.of(charge));
        when(loanProductRepository.retrieveLoanProductsByChargeId(CHARGE_ID)).thenReturn(List.of());
        when(chargeRepository.isAnyWorkingCapitalLoansAssociateWithThisCharge(CHARGE_ID)).thenReturn(Optional.of(1L));
        stubGuardQueries("false", "false");

        assertThrows(ChargeCannotBeDeletedException.class, () -> service.deleteCharge(CHARGE_ID));
        verify(chargeRepository, never()).save(any(Charge.class));
    }
}
