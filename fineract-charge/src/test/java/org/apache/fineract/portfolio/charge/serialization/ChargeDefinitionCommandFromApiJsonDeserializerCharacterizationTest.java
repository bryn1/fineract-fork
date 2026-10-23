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
package org.apache.fineract.portfolio.charge.serialization;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.Test;

/**
 * S4a characterization suite (MC 11183): pins the CURRENT observable validation behavior of
 * {@link ChargeDefinitionCommandFromApiJsonDeserializer} at pin 355287c8 — what IS, not what should be.
 * Nothing here is a fix request; suspect pins are labeled and carry rows in research/04 "S4 suspect map".
 */
class ChargeDefinitionCommandFromApiJsonDeserializerCharacterizationTest {

    private final ChargeDefinitionCommandFromApiJsonDeserializer deserializer = new ChargeDefinitionCommandFromApiJsonDeserializer(
            new FromJsonHelper());

    private static void assertErrorForParameter(PlatformApiDataValidationException ex, String parameterName) {
        assertTrue(ex.getErrors().stream().anyMatch(e -> parameterName.equals(e.getParameterName())),
                "Expected validation error for parameter '" + parameterName + "', got: " + ex.getErrors());
    }

    private static List<ApiParameterError> errorsForParameter(PlatformApiDataValidationException ex, String parameterName) {
        return ex.getErrors().stream().filter(e -> parameterName.equals(e.getParameterName())).toList();
    }

    /**
     * Assembles a charge-create payload: structurally valid LOAN charge (appliesTo=1, time=1 DISBURSEMENT,
     * calc=1 FLAT, mode=0 REGULAR, name/currency/amount present). Overrides are "key=rawJsonValue" pairs —
     * an existing key is REPLACED, "key=" with empty value REMOVES it.
     */
    private static String loanChargeJson(String... overrides) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("chargeAppliesTo", "1");
        fields.put("chargeTimeType", "1");
        fields.put("chargeCalculationType", "1");
        fields.put("chargePaymentMode", "0");
        fields.put("name", "\"S\"");
        fields.put("currencyCode", "\"USD\"");
        fields.put("amount", "10");
        for (String override : overrides) {
            int split = override.indexOf('=');
            String key = override.substring(0, split);
            String value = override.substring(split + 1);
            if (value.isEmpty()) {
                fields.remove(key);
            } else {
                fields.put(key, value);
            }
        }
        StringBuilder sb = new StringBuilder("{");
        fields.forEach((k, v) -> sb.append('"').append(k).append("\":").append(v).append(','));
        sb.setLength(sb.length() - 1);
        return sb.append('}').toString();
    }

    private PlatformApiDataValidationException expectValidationFailure(String json) {
        return assertThrows(PlatformApiDataValidationException.class, () -> deserializer.validateForCreate(json));
    }

    // ---- create: envelope and required parameters ----

    @Test
    void createRejectsBlankJsonWithInvalidJsonException() {
        assertThrows(InvalidJsonException.class, () -> deserializer.validateForCreate(""));
    }

    @Test
    void createRejectsUnknownParameterWithUnsupportedParameterException() {
        // the gate is UnsupportedParameterException, not a field-level validation error
        UnsupportedParameterException ex = assertThrows(UnsupportedParameterException.class,
                () -> deserializer.validateForCreate(loanChargeJson("bogusParam=1")));
        assertTrue(ex.getUnsupportedParameters().contains("bogusParam"));
    }

    @Test
    void createValidMinimalLoanChargePasses() {
        assertDoesNotThrow(() -> deserializer.validateForCreate(loanChargeJson()));
    }

    @Test
    void createRequiresNameAmountAndThreeCharacterCurrency() {
        assertErrorForParameter(expectValidationFailure(loanChargeJson("name=")), "name");
        assertErrorForParameter(expectValidationFailure(loanChargeJson("amount=")), "amount");
        assertErrorForParameter(expectValidationFailure(loanChargeJson("currencyCode=\"USDD\"")), "currencyCode");
    }

    @Test
    void createRejectsNameLongerThan100AndNegativeAmount() {
        assertErrorForParameter(expectValidationFailure(loanChargeJson("name=\"" + "n".repeat(101) + "\"")), "name");
        assertErrorForParameter(expectValidationFailure(loanChargeJson("amount=-5")), "amount");
    }

    @Test
    void createRejectsExplicitNullPenaltyAndActive() {
        // boxed-Boolean fields under a notNull() rule: an explicit null lands a clean validation error
        assertErrorForParameter(expectValidationFailure(loanChargeJson("penalty=null")), "penalty");
        assertErrorForParameter(expectValidationFailure(loanChargeJson("active=null")), "active");
    }

    // ---- create: fee interval/frequency guards ----

    @Test
    void createRequiresFeeIntervalWhenFeeFrequencyIsPresent() {
        assertErrorForParameter(expectValidationFailure(loanChargeJson("feeFrequency=2")), "feeInterval");
    }

    @Test
    void createRejectsFeeIntervalZeroAndFeeFrequencyOutOfRange() {
        assertErrorForParameter(expectValidationFailure(loanChargeJson("feeInterval=0")), "feeInterval");
        assertErrorForParameter(expectValidationFailure(loanChargeJson("feeFrequency=4", "feeInterval=3")), "feeFrequency");
    }

    @Test
    void createWithEnablePaymentTypeTrueButMissingPaymentTypeIdPassesValidation() {
        // CHARACTERIZED (suspect — S4 suspect map S-C2): integerGreaterThanZero SKIPS null values
        // (DataValidatorBuilder: null falls through with no error unless notNull() was chained), so
        // enablePaymentType=true with NO paymentTypeId passes the deserializer; the write service then
        // stores isPaymentType=true with a null payment type.
        assertDoesNotThrow(() -> deserializer.validateForCreate(loanChargeJson("enablePaymentType=true")));
    }

    @Test
    void createWithEnablePaymentTypeTrueAndZeroPaymentTypeIdIsRejected() {
        // the negative control for the null-skip above: a PRESENT value is enforced
        assertErrorForParameter(expectValidationFailure(loanChargeJson("enablePaymentType=true,paymentTypeId=0")), "paymentTypeId");
    }

    @Test
    void createWithEnableFreeWithdrawalTrueButMissingFrequenciesPassesValidation() {
        // CHARACTERIZED (suspect — S4 suspect map S-C2): same null-skip on both frequency guards;
        // countFrequencyType is extracted but carries NO rule at all on the free-withdrawal path.
        assertDoesNotThrow(() -> deserializer.validateForCreate(loanChargeJson("enableFreeWithdrawalCharge=true")));
    }

    @Test
    void createWithEnableFreeWithdrawalTrueAndZeroFrequencyIsRejected() {
        assertErrorForParameter(expectValidationFailure(loanChargeJson("enableFreeWithdrawalCharge=true,freeWithdrawalFrequency=0")),
                "freeWithdrawalFrequency");
    }

    @Test
    void createWithExplicitNullEnableFreeWithdrawalChargeFailsWithNpeInsteadOfValidationError() {
        // CHARACTERIZED (suspect — S4 suspect map S-C1): the parameterExists gate passes for an explicit
        // JSON null, extractBooleanNamed returns null, the notNull() rule records its error — and the very
        // next line unboxes the null Boolean into `if (enableFreeWithdrawalCharge)`: NullPointerException
        // (500 family) where a 400 validation error is owed.
        assertThrows(NullPointerException.class,
                () -> deserializer.validateForCreate(loanChargeJson("enableFreeWithdrawalCharge=null")));
    }

    @Test
    void createWithExplicitNullEnablePaymentTypeFailsWithNpeInsteadOfValidationError() {
        // CHARACTERIZED (suspect — S4 suspect map S-C1): same shape, extracted straight into a primitive
        // boolean — the NPE fires at the extraction line itself.
        assertThrows(NullPointerException.class, () -> deserializer.validateForCreate(loanChargeJson("enablePaymentType=null")));
    }

    // ---- create: appliesTo branch dispatch ----

    @Test
    void createWithUnknownAppliesToValueIsRejectedButAllBranchValidationIsSkipped() {
        // CHARACTERIZED (suspect — S4 suspect map S-C3): chargeAppliesTo=99 is reported (not in validValues)
        // but ChargeAppliesTo.fromInt collapses it to INVALID, no branch runs, and the per-branch required
        // parameters (chargeTimeType, chargePaymentMode) are never even checked.
        PlatformApiDataValidationException ex = expectValidationFailure(
                "{\"chargeAppliesTo\":99,\"chargeCalculationType\":1,\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10}");
        assertErrorForParameter(ex, "chargeAppliesTo");
        assertFalse(ex.getErrors().stream().anyMatch(e -> "chargeTimeType".equals(e.getParameterName())),
                "branch validation must not have run for an unknown appliesTo value, got: " + ex.getErrors());
    }

    @Test
    void createLoanChargeRequiresPaymentModeAndTime() {
        PlatformApiDataValidationException ex = expectValidationFailure(
                "{\"chargeAppliesTo\":1,\"chargeCalculationType\":1,\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10}");
        assertErrorForParameter(ex, "chargeTimeType");
        assertErrorForParameter(ex, "chargePaymentMode");
    }

    @Test
    void createLoanDisbursementRejectsPercentOfDisbursementAmount() {
        // chargeCalculationType=5 IS in validValuesForLoan, yet the cross-field rule rejects it for every
        // non-tranche time — the two layers can disagree and the stricter one wins; exactly one error.
        PlatformApiDataValidationException ex = expectValidationFailure(loanChargeJson("chargeCalculationType=5"));
        assertEquals(1, errorsForParameter(ex, "chargeCalculationType").size());
    }

    @Test
    void createLoanTrancheDisbursementAcceptsFlatAndPercentOfDisbursementOnly() {
        assertDoesNotThrow(() -> deserializer.validateForCreate(loanChargeJson("chargeTimeType=12", "chargeCalculationType=5")));
        assertErrorForParameter(expectValidationFailure(loanChargeJson("chargeTimeType=12", "chargeCalculationType=2")),
                "chargeCalculationType");
    }

    @Test
    void createSavingsMonthlyFeeRequiresFeeOnMonthDayAndIntervalIn1To12() {
        String base = "\"chargeAppliesTo\":2,\"chargeTimeType\":7,\"chargeCalculationType\":1,\"name\":\"S\","
                + "\"currencyCode\":\"USD\",\"amount\":10";
        assertErrorForParameter(expectValidationFailure("{" + base + "}"), "feeOnMonthDay");
        assertErrorForParameter(
                expectValidationFailure("{" + base + ",\"locale\":\"en\",\"monthDayFormat\":\"dd-MM\",\"feeOnMonthDay\":\"15-01\"}"),
                "feeInterval");
        assertDoesNotThrow(() -> deserializer.validateForCreate("{" + base
                + ",\"locale\":\"en\",\"monthDayFormat\":\"dd-MM\",\"feeOnMonthDay\":\"15-01\",\"feeInterval\":1}"));
    }

    @Test
    void createSavingsMonthlyFeeWithoutLocaleFailsWithNpe() {
        // CHARACTERIZED (suspect — S4 suspect map S-C6): a legitimate monthly-fee payload with monthDayFormat
        // and feeOnMonthDay but NO locale NPEs inside the core month-day extractor
        // (DateTimeFormatterBuilder.toFormatter(null)) — 500 family where a 400 is owed. The deserializer
        // calls the frozen extractor unguarded; legacy form clients always send locale, which masks it.
        assertThrows(NullPointerException.class, () -> deserializer.validateForCreate("{\"chargeAppliesTo\":2,\"chargeTimeType\":7,"
                + "\"chargeCalculationType\":1,\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10,"
                + "\"monthDayFormat\":\"dd-MM\",\"feeOnMonthDay\":\"15-01\"}"));
    }

    @Test
    void createSavingsWeeklyFeeRejectsAnyProvidedFeeOnMonthDay() {
        assertErrorForParameter(
                expectValidationFailure("{\"chargeAppliesTo\":2,\"chargeTimeType\":11,\"chargeCalculationType\":1,\"name\":\"S\","
                        + "\"currencyCode\":\"USD\",\"amount\":10,\"locale\":\"en\",\"monthDayFormat\":\"dd-MM\","
                        + "\"feeOnMonthDay\":\"15-01\"}"),
                "feeOnMonthDay");
    }

    @Test
    void createSavingsChargeRejectsPercentOfInterest() {
        assertErrorForParameter(
                expectValidationFailure("{\"chargeAppliesTo\":2,\"chargeTimeType\":5,\"chargeCalculationType\":4,\"name\":\"S\","
                        + "\"currencyCode\":\"USD\",\"amount\":10}"),
                "chargeCalculationType");
    }

    @Test
    void createClientChargeAcceptsSpecifiedDueDateFlatWithOptionalPositiveGlAccount() {
        assertDoesNotThrow(() -> deserializer.validateForCreate("{\"chargeAppliesTo\":3,\"chargeTimeType\":2,"
                + "\"chargeCalculationType\":1,\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10}"));
        assertErrorForParameter(expectValidationFailure("{\"chargeAppliesTo\":3,\"chargeTimeType\":1,\"chargeCalculationType\":1,"
                + "\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10}"), "chargeTimeType");
        assertErrorForParameter(expectValidationFailure("{\"chargeAppliesTo\":3,\"chargeTimeType\":2,\"chargeCalculationType\":1,"
                + "\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10,\"incomeAccountId\":0}"), "incomeAccountId");
    }

    @Test
    void createShareAccountActivationAcceptsFlatOnly() {
        assertDoesNotThrow(() -> deserializer.validateForCreate("{\"chargeAppliesTo\":4,\"chargeTimeType\":13,"
                + "\"chargeCalculationType\":1,\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10}"));
        // chargeCalculationType=2 passes validValuesForShares but fails the share-activation-specific rule
        assertErrorForParameter(
                expectValidationFailure("{\"chargeAppliesTo\":4,\"chargeTimeType\":13,\"chargeCalculationType\":2,"
                        + "\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10}"),
                "chargeCalculationType");
    }

    @Test
    void createWorkingCapitalLoanBranchEnforcesTimeCalculationAndPaymentMode() {
        // time must be DISBURSEMENT(1) or SPECIFIED_DUE_DATE(2); DISBURSEMENT allows FLAT+%amount,
        // SPECIFIED_DUE_DATE FLAT only; payment mode may only be REGULAR when present.
        assertDoesNotThrow(() -> deserializer.validateForCreate("{\"chargeAppliesTo\":5,\"chargeTimeType\":1,"
                + "\"chargeCalculationType\":2,\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10}"));
        assertErrorForParameter(expectValidationFailure("{\"chargeAppliesTo\":5,\"chargeTimeType\":8,"
                + "\"chargeCalculationType\":1,\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10}"), "chargeTimeType");
        assertErrorForParameter(expectValidationFailure("{\"chargeAppliesTo\":5,\"chargeTimeType\":2,"
                + "\"chargeCalculationType\":2,\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10}"), "chargeCalculationType");
        assertErrorForParameter(expectValidationFailure("{\"chargeAppliesTo\":5,\"chargeTimeType\":1,"
                + "\"chargeCalculationType\":1,\"chargePaymentMode\":1,\"name\":\"S\",\"currencyCode\":\"USD\",\"amount\":10}"),
                "chargePaymentMode");
    }

    // ---- update ----

    private PlatformApiDataValidationException expectUpdateFailure(String json) {
        return assertThrows(PlatformApiDataValidationException.class, () -> deserializer.validateForUpdate(json));
    }

    @Test
    void updateOfEmptyJsonObjectPassesValidation() {
        // CHARACTERIZED (suspect — S4 suspect map S-C4): unlike the GL-account command, the charge update
        // has NO at-least-one-parameter guard — an empty PUT validates clean and then runs the whole
        // service update path with an empty change map.
        assertDoesNotThrow(() -> deserializer.validateForUpdate("{}"));
    }

    @Test
    void updateOfBlankJsonFailsWithInvalidJsonException() {
        assertThrows(InvalidJsonException.class, () -> deserializer.validateForUpdate("  "));
    }

    @Test
    void updateRejectsUnknownParameterWithUnsupportedParameterException() {
        assertThrows(UnsupportedParameterException.class, () -> deserializer.validateForUpdate("{\"bogusParam\":1}"));
    }

    @Test
    void updateReportsTheChargeAppliesToErrorThreeTimes() {
        // CHARACTERIZED (suspect — S4 suspect map S-C5): the identical chargeAppliesTo block is copied
        // THREE times in validateForUpdate (as-is at 355287c8), so one bad value yields three identical
        // ApiParameterError entries in the response.
        PlatformApiDataValidationException ex = expectUpdateFailure("{\"chargeAppliesTo\":99}");
        assertEquals(3, errorsForParameter(ex, "chargeAppliesTo").size());
    }

    @Test
    void updateReportsMinCapAndMaxCapErrorsTwiceEach() {
        // CHARACTERIZED (suspect — S4 suspect map S-C5): minCap/maxCap are also validated in TWO identical
        // blocks, so each bad cap produces two duplicate errors.
        PlatformApiDataValidationException ex = expectUpdateFailure("{\"minCap\":-1,\"maxCap\":-2}");
        assertEquals(2, errorsForParameter(ex, "minCap").size());
        assertEquals(2, errorsForParameter(ex, "maxCap").size());
    }

    @Test
    void updateAcceptsPartialFieldChanges() {
        assertDoesNotThrow(() -> deserializer.validateForUpdate("{\"name\":\"New name\"}"));
        assertDoesNotThrow(() -> deserializer.validateForUpdate("{\"amount\":5}"));
        assertDoesNotThrow(() -> deserializer.validateForUpdate("{\"chargeTimeType\":5}")); // WITHDRAWAL_FEE — union set
        assertDoesNotThrow(() -> deserializer.validateForUpdate("{\"chargePaymentMode\":1}"));
        assertDoesNotThrow(() -> deserializer.validateForUpdate("{\"locale\":\"en\",\"monthDayFormat\":\"dd-MM\","
                + "\"feeOnMonthDay\":\"15-01\"}"));
    }

    @Test
    void updateRejectsOutOfRangeTimeCalculationAndPaymentModeValues() {
        // update validates chargeTimeType against the UNION of loan+savings+client+share sets — not the
        // entity's own appliesTo — and chargeCalculationType by flat range 1..5
        assertErrorForParameter(expectUpdateFailure("{\"chargeTimeType\":99}"), "chargeTimeType");
        assertErrorForParameter(expectUpdateFailure("{\"chargeCalculationType\":6}"), "chargeCalculationType");
        assertErrorForParameter(expectUpdateFailure("{\"chargeCalculationType\":0}"), "chargeCalculationType");
        assertErrorForParameter(expectUpdateFailure("{\"chargePaymentMode\":2}"), "chargePaymentMode");
    }

    @Test
    void updateWithExplicitNullChargePaymentModePassesSilently() {
        // pinned (kept-legit, legacy partial-update idiom): an explicit null falls through inMinMaxRange
        // with no error — null means "not sent" on the update path
        assertDoesNotThrow(() -> deserializer.validateForUpdate("{\"chargePaymentMode\":null}"));
    }

    @Test
    void updateRejectsNonPositiveLinkIds() {
        assertErrorForParameter(expectUpdateFailure("{\"incomeAccountId\":0}"), "incomeAccountId");
        assertErrorForParameter(expectUpdateFailure("{\"taxGroupId\":-1}"), "taxGroupId");
    }

    // ---- public cross-field entry point used by the write service ----

    @Test
    void validateChargeTimeNCalculationTypeEnforcesTheTranchePairingPublicly() {
        assertDoesNotThrow(() -> deserializer.validateChargeTimeNCalculationType(12, 5)); // tranche + %disbursement
        assertDoesNotThrow(() -> deserializer.validateChargeTimeNCalculationType(12, 1)); // tranche + flat
        PlatformApiDataValidationException ex = assertThrows(PlatformApiDataValidationException.class,
                () -> deserializer.validateChargeTimeNCalculationType(12, 2));
        assertErrorForParameter(ex, "chargeCalculationType");
        assertThrows(PlatformApiDataValidationException.class, () -> deserializer.validateChargeTimeNCalculationType(1, 5));
    }
}
