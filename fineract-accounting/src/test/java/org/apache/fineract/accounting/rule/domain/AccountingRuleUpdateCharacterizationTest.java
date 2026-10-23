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
package org.apache.fineract.accounting.rule.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryType;
import org.apache.fineract.infrastructure.codes.domain.CodeValue;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.Test;

/**
 * S1 characterization suite (MC 11150): pins entity-level update/tag semantics of
 * {@link AccountingRule} at pin 355287c8 — the changes-map vs applied-property divergence.
 */
class AccountingRuleUpdateCharacterizationTest {

    private final FromJsonHelper fromJsonHelper = new FromJsonHelper();

    private JsonCommand commandFrom(String json) {
        return JsonCommand.from(json, fromJsonHelper.parse(json), fromJsonHelper, "accountingRule", null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    @Test
    void fromJsonTrimsTheDescriptionAndHardcodesSystemDefinedFalse() {
        JsonCommand command = commandFrom("{\"name\":\"R\",\"description\":\"  padded  \"}");

        AccountingRule rule = AccountingRule.fromJson(null, null, null, command, false, false);

        assertEquals("padded", rule.getDescription());
        assertEquals(Boolean.FALSE, rule.getSystemDefined());
    }

    @Test
    void nameChangeIsReportedAndApplied() {
        AccountingRule rule = new AccountingRule();
        rule.setName("Old");
        rule.setSystemDefined(false);

        var changes = rule.update(commandFrom("{\"name\":\"New\"}"));

        assertEquals("New", changes.get("name"));
        assertEquals("New", rule.getName());
    }

    @Test
    void updateOnFreshlyConstructedEntityWithoutSystemDefinedThrowsNpe() {
        // CHARACTERIZED (suspect — candidate for S2 decision): update() passes this.systemDefined (a nullable
        // Boolean) into the boolean overload of handlePropertyUpdate, whose parameter is a primitive — the
        // call-site unboxing of the null field throws. Only rules built by fromJson() or loaded from the DB
        // (system_defined nullable=false column) can be updated; the entity cannot defend against update()
        // before persistence.
        AccountingRule rule = new AccountingRule();
        rule.setName("Old");

        assertThrows(NullPointerException.class, () -> rule.update(commandFrom("{\"name\":\"New\"}")));
    }

    @Test
    void accountToCreditChangeIsReportedButNOTAppliedByUpdateItself() {
        // nested property: the write-service applies accountToCredit afterwards; the entity update only
        // records it in the changes map. Pinned so S2 keeps who-applies-what where it is today.
        GLAccount oldAccount = new GLAccount();
        oldAccount.setId(5L);
        AccountingRule rule = new AccountingRule();
        rule.setSystemDefined(false);
        rule.setAccountToCredit(oldAccount);

        var changes = rule.update(commandFrom("{\"accountToCredit\":9}"));

        assertEquals(9L, changes.get("accountToCredit"));
        assertEquals(5L, rule.getAccountToCredit().getId());
    }

    @Test
    void allowMultipleCreditEntriesIsReportedAsChangedEvenWhenDeliberatelyNotApplied() {
        // CHARACTERIZED (suspect — candidate for S2 decision): the boolean is guarded by accountToCredit == null
        // before the property is written, but the change is put into the map UNCONDITIONALLY — the API result
        // therefore reports a change that the entity refused to apply.
        GLAccount creditAccount = new GLAccount();
        creditAccount.setId(5L);
        AccountingRule rule = new AccountingRule();
        rule.setSystemDefined(false);
        rule.setAccountToCredit(creditAccount);
        rule.setAllowMultipleCreditEntries(false);

        var changes = rule.update(commandFrom("{\"allowMultipleCreditEntries\":true}"));

        assertEquals(Boolean.TRUE, changes.get("allowMultipleCreditEntries"));
        assertFalse(rule.isAllowMultipleCreditEntries(), "property must stay unchanged while an account-to-credit is set");
    }

    @Test
    void allowMultipleDebitEntriesIsAppliedWhenNoDebitAccountIsSet() {
        AccountingRule rule = new AccountingRule();
        rule.setSystemDefined(false);
        rule.setAccountToDebit(null);
        rule.setAllowMultipleDebitEntries(false);

        var changes = rule.update(commandFrom("{\"allowMultipleDebitEntries\":true}"));

        assertEquals(Boolean.TRUE, changes.get("allowMultipleDebitEntries"));
        assertTrue(rule.isAllowMultipleDebitEntries());
    }

    @Test
    void updateTagsRetainOnlyTheGivenJournalEntryType() {
        AccountingRule rule = new AccountingRule();
        rule.updateAccountingRuleForTags(
                java.util.List.of(AccountingTagRule.create(new CodeValue(), JournalEntryType.DEBIT.getValue()),
                        AccountingTagRule.create(new CodeValue(), JournalEntryType.CREDIT.getValue())));
        assertEquals(2, rule.getAccountingTagRules().size());

        rule.updateTags(JournalEntryType.CREDIT);

        assertEquals(1, rule.getAccountingTagRules().size());
        assertEquals(JournalEntryType.CREDIT.getValue(), rule.getAccountingTagRules().get(0).getAccountType());
    }

    @Test
    void getAccountingTagRulesByTypeReturnsNothingForAnUnrelatedRule() {
        AccountingRule rule = new AccountingRule();
        rule.updateAccountingRuleForTags(java.util.List.of(AccountingTagRule.create(new CodeValue(), JournalEntryType.DEBIT.getValue())));

        assertTrue(rule.getAccountingTagRulesByType(JournalEntryType.CREDIT).isEmpty());
        assertEquals(1, rule.getAccountingTagRulesByType(JournalEntryType.DEBIT).size());
    }

    @Test
    void removeOldTagsDropsMatchingTagRules() {
        AccountingTagRule doomed = AccountingTagRule.create(new CodeValue(), JournalEntryType.DEBIT.getValue());
        AccountingRule rule = new AccountingRule();
        rule.updateAccountingRuleForTags(java.util.List.of(doomed));

        rule.removeOldTags(java.util.List.of(doomed));

        assertNull(rule.getAccountingTagRules().stream().filter(t -> t == doomed).findFirst().orElse(null));
        assertTrue(rule.getAccountingTagRules().isEmpty());
    }
}
