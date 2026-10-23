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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.portfolio.charge.domain.ChargeAppliesTo;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargePaymentMode;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.junit.jupiter.api.Test;

/**
 * S4a characterization suite (MC 11183): pins the CURRENT display-label mapping surface of
 * {@link ChargeEnumerations} at pin 355287c8 — the exact (id, code, description) triples that land in
 * API JSON. Suspect pins are labeled; rows in research/04 "S4 suspect map".
 */
class ChargeEnumerationsCharacterizationTest {

    private static void assertOption(EnumOptionData actual, long id, String code, String description) {
        assertEquals(Long.valueOf(id), actual.getId());
        assertEquals(code, actual.getCode());
        assertEquals(description, actual.getValue());
    }

    @Test
    void chargeTimeTypeMapsEveryKnownIdAndCollapsesUnknownToInvalid() {
        assertOption(ChargeEnumerations.chargeTimeType(1), 1, "chargeTimeType.disbursement", "Disbursement");
        assertOption(ChargeEnumerations.chargeTimeType(2), 2, "chargeTimeType.specifiedDueDate", "Specified due date");
        assertOption(ChargeEnumerations.chargeTimeType(3), 3, "chargeTimeType.savingsActivation", "Savings Activation");
        assertOption(ChargeEnumerations.chargeTimeType(4), 4, "chargeTimeType.savingsClosure", "Savings Closure");
        assertOption(ChargeEnumerations.chargeTimeType(5), 5, "chargeTimeType.withdrawalFee", "Withdrawal Fee");
        assertOption(ChargeEnumerations.chargeTimeType(6), 6, "chargeTimeType.annualFee", "Annual Fee");
        assertOption(ChargeEnumerations.chargeTimeType(7), 7, "chargeTimeType.monthlyFee", "Monthly Fee");
        assertOption(ChargeEnumerations.chargeTimeType(8), 8, "chargeTimeType.instalmentFee", "Installment Fee");
        assertOption(ChargeEnumerations.chargeTimeType(9), 9, "chargeTimeType.overdueInstallment", "Overdue Fees");
        assertOption(ChargeEnumerations.chargeTimeType(10), 10, "chargeTimeType.overdraftFee", "Overdraft Fee");
        assertOption(ChargeEnumerations.chargeTimeType(11), 11, "chargeTimeType.weeklyFee", "Weekly Fee");
        assertOption(ChargeEnumerations.chargeTimeType(12), 12, "chargeTimeType.tranchedisbursement", "Tranche Disbursement");
        assertOption(ChargeEnumerations.chargeTimeType(13), 13, "chargeTimeType.activation", "Share Account Activate");
        assertOption(ChargeEnumerations.chargeTimeType(14), 14, "chargeTimeType.sharespurchase", "Share Purchase");
        assertOption(ChargeEnumerations.chargeTimeType(15), 15, "chargeTimeType.sharesredeem", "Share Redeem");
        assertOption(ChargeEnumerations.chargeTimeType(16), 16, "chargeTimeType.savingsNoActivityFee", "Saving No Activity Fee");
        assertOption(ChargeEnumerations.chargeTimeType(99), 0, "chargeTimeType.invalid", "Invalid");
    }

    @Test
    void chargeAppliesToMapsKnownIdsAndCollapsesUnknownToInvalid() {
        assertOption(ChargeEnumerations.chargeAppliesTo(1), 1, "chargeAppliesTo.loan", "Loan");
        assertOption(ChargeEnumerations.chargeAppliesTo(2), 2, "chargeAppliesTo.savings", "Savings");
        assertOption(ChargeEnumerations.chargeAppliesTo(3), 3, "chargeAppliesTo.client", "Client");
        assertOption(ChargeEnumerations.chargeAppliesTo(4), 4, "chargeAppliesTo.shares", "Shares");
        assertOption(ChargeEnumerations.chargeAppliesTo(5), 5, "chargeAppliesTo.workingCapitalLoan", "Working Capital Loan");
        assertOption(ChargeEnumerations.chargeAppliesTo(99), 0, "chargeAppliesTo.invalid", "Invalid");
    }

    @Test
    void chargeCalculationTypeMapsKnownIdsAndCollapsesUnknownToInvalid() {
        assertOption(ChargeEnumerations.chargeCalculationType(1), 1, "chargeCalculationType.flat", "Flat");
        assertOption(ChargeEnumerations.chargeCalculationType(2), 2, "chargeCalculationType.percent.of.amount", "% Amount");
        assertOption(ChargeEnumerations.chargeCalculationType(3), 3, "chargeCalculationType.percent.of.amount.and.interest",
                "% Loan Amount + Interest");
        assertOption(ChargeEnumerations.chargeCalculationType(4), 4, "chargeCalculationType.percent.of.interest", "% Interest");
        assertOption(ChargeEnumerations.chargeCalculationType(5), 5, "chargeCalculationType.percent.of.disbursement.amount",
                "% Disbursement Amount");
        assertOption(ChargeEnumerations.chargeCalculationType(99), 0, "chargeCalculationType.invalid", "Invalid");
    }

    @Test
    void chargePaymentModeHasNoInvalidRepresentationAtAll() {
        // CHARACTERIZED (suspect — S4 suspect map S-C8): any id other than 1 displays as "Regular" —
        // there is no "Invalid" option for this enum, corrupt stored values are indistinguishable from
        // the legitimate REGULAR choice.
        assertOption(ChargeEnumerations.chargePaymentMode(1), 1, "chargepaymentmode.accounttransfer", "Account transfer");
        assertOption(ChargeEnumerations.chargePaymentMode(0), 0, "chargepaymentmode.regular", "Regular");
        assertOption(ChargeEnumerations.chargePaymentMode(99), 0, "chargepaymentmode.regular", "Regular");
    }

    @Test
    void feeFrequencyTypeThrowsUnsupportedOperationExceptionForTheInvalidMember() {
        // supported set is DAYS..WHOLE_TERM (0..4); the INVALID member (5) — and every unknown id that
        // collapses to it — is a hard UnsupportedOperationException, unlike the other mappings here
        assertOption(ChargeEnumerations.feeFrequencyType(0), 0, "periodFrequencyType.days", "Daily");
        assertOption(ChargeEnumerations.feeFrequencyType(1), 1, "periodFrequencyType.weeks", "Weekly");
        assertOption(ChargeEnumerations.feeFrequencyType(2), 2, "periodFrequencyType.months", "Monthly");
        assertOption(ChargeEnumerations.feeFrequencyType(3), 3, "periodFrequencyType.years", "Yearly");
        assertOption(ChargeEnumerations.feeFrequencyType(4), 4, "periodFrequencyType.whole_term", "Whole term");
        assertThrows(UnsupportedOperationException.class, () -> ChargeEnumerations.feeFrequencyType(5));
        assertThrows(UnsupportedOperationException.class, () -> ChargeEnumerations.feeFrequencyType(99));
        assertThrows(UnsupportedOperationException.class, () -> ChargeEnumerations.feeFrequencyType(PeriodFrequencyType.INVALID));
    }

    @Test
    void enumObjectOverloadsMatchTheIntOverloads() {
        assertOption(ChargeEnumerations.chargeTimeType(ChargeTimeType.SHARE_REDEEM), 15, "chargeTimeType.sharesredeem", "Share Redeem");
        assertOption(ChargeEnumerations.chargeAppliesTo(ChargeAppliesTo.CLIENT), 3, "chargeAppliesTo.client", "Client");
        assertOption(ChargeEnumerations.chargeCalculationType(ChargeCalculationType.FLAT), 1, "chargeCalculationType.flat", "Flat");
        assertOption(ChargeEnumerations.chargePaymentMode(ChargePaymentMode.ACCOUNT_TRANSFER), 1, "chargepaymentmode.accounttransfer",
                "Account transfer");
    }

    @Test
    void workingCapitalTimeTypeUnionsAreStableForTheDropdown() {
        List<ChargeTimeType> productTimes = ChargeTimeType.validWorkingCapitalLoanProduct();
        assertEquals(Arrays.asList(ChargeTimeType.DISBURSEMENT, ChargeTimeType.SPECIFIED_DUE_DATE), productTimes);
        assertEquals(List.of(ChargeTimeType.SPECIFIED_DUE_DATE), ChargeTimeType.validWorkingCapitalLoanAccount());
    }
}
