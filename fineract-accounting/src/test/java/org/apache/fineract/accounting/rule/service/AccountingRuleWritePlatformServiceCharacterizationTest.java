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
package org.apache.fineract.accounting.rule.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepositoryWrapper;
import org.apache.fineract.accounting.rule.domain.AccountingRule;
import org.apache.fineract.accounting.rule.domain.AccountingRuleRepository;
import org.apache.fineract.accounting.rule.domain.AccountingRuleRepositoryWrapper;
import org.apache.fineract.accounting.rule.exception.AccountingRuleDataException;
import org.apache.fineract.accounting.rule.exception.AccountingRuleDuplicateException;
import org.apache.fineract.accounting.rule.serialization.AccountingRuleCommandFromApiJsonDeserializer;
import org.apache.fineract.infrastructure.codes.domain.CodeValue;
import org.apache.fineract.infrastructure.codes.domain.CodeValueRepository;
import org.apache.fineract.infrastructure.codes.exception.CodeValueNotFoundException;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepositoryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * S1 characterization suite (MC 11150): pins write-path behavior of
 * {@link AccountingRuleWritePlatformServiceJpaRepositoryImpl} at pin 355287c8 (unit-level, Mockito, DB-free).
 */
@ExtendWith(MockitoExtension.class)
class AccountingRuleWritePlatformServiceCharacterizationTest {

    @Mock
    private AccountingRuleRepositoryWrapper accountingRuleRepositoryWrapper;
    @Mock
    private AccountingRuleRepository accountingRuleRepository;
    @Mock
    private GLAccountRepositoryWrapper accountRepositoryWrapper;
    @Mock
    private OfficeRepositoryWrapper officeRepositoryWrapper;
    @Mock
    private CodeValueRepository codeValueRepository;

    private final FromJsonHelper fromJsonHelper = new FromJsonHelper();

    private AccountingRuleWritePlatformServiceJpaRepositoryImpl service;

    @BeforeEach
    void setUp() {
        // real deserializer on purpose: pins validation+service composition as it stands at the pin
        service = new AccountingRuleWritePlatformServiceJpaRepositoryImpl(accountingRuleRepositoryWrapper, accountingRuleRepository,
                accountRepositoryWrapper, officeRepositoryWrapper,
                new AccountingRuleCommandFromApiJsonDeserializer(fromJsonHelper), codeValueRepository);
    }

    private JsonCommand commandFrom(String json) {
        return JsonCommand.from(json, fromJsonHelper.parse(json), fromJsonHelper, "accountingRule", null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    @Test
    void createWithBothDebitAccountAndDebitTagsIsRejectedByTheServiceNotTheValidator() {
        // CHARACTERIZED (suspect — candidate for S2 decision): the account-XOR-tags contract is NOT enforced in
        // validateForCreate (both sides pass JSON validation) — only the service enforces it, and only on create.
        when(officeRepositoryWrapper.findOneWithNotFoundDetection(1L)).thenReturn(mock(Office.class));

        JsonCommand command = commandFrom(
                "{\"officeId\":1,\"name\":\"Mixed rule\",\"accountToDebit\":10,\"debitTags\":[1],\"accountToCredit\":11}");

        AccountingRuleDataException ex = assertThrows(AccountingRuleDataException.class, () -> service.createAccountingRule(command));
        assertEquals("error.msg.accounting.rule.accountToDebit.or.debitTags.required", ex.getGlobalisationMessageCode());
    }

    @Test
    void createWithNeitherDebitAccountNorDebitTagsIsCaughtByValidationFirst() {
        // the SAME contract is enforced a second time (differently) inside the deserializer: missing BOTH
        // debit sides yields a validation error under the combined parameter name "accountToDebit,debitTags".
        JsonCommand command = commandFrom("{\"officeId\":1,\"name\":\"Half rule\",\"accountToCredit\":11}");

        PlatformApiDataValidationException ex = assertThrows(PlatformApiDataValidationException.class,
                () -> service.createAccountingRule(command));
        assertEquals("accountToDebit,debitTags", ex.getErrors().get(0).getParameterName());
    }

    @Test
    void createTagBasedRuleResolvesEveryTagThroughCodeValueRepositoryAndReturnsTheRuleId() {
        when(officeRepositoryWrapper.findOneWithNotFoundDetection(1L)).thenReturn(mock(Office.class));
        when(codeValueRepository.findById(1L)).thenReturn(Optional.of(new CodeValue()));
        when(codeValueRepository.findById(2L)).thenReturn(Optional.of(new CodeValue()));
        when(accountingRuleRepository.saveAndFlush(any(AccountingRule.class))).thenAnswer(invocation -> {
            AccountingRule rule = invocation.getArgument(0);
            rule.setId(55L);
            return rule;
        });

        JsonCommand command = commandFrom(
                "{\"officeId\":1,\"name\":\"Tag rule\",\"debitTags\":[1],\"creditTags\":[2],\"allowMultipleDebitEntries\":false,"
                        + "\"allowMultipleCreditEntries\":false}");

        CommandProcessingResult result = service.createAccountingRule(command);

        assertEquals(55L, result.getResourceId());
        assertEquals(1L, result.getOfficeId());
        verify(codeValueRepository).findById(1L);
        verify(codeValueRepository).findById(2L);
        verify(accountingRuleRepository).saveAndFlush(any(AccountingRule.class));
    }

    @Test
    void createTagBasedRuleWithUnknownTagIdFailsWithCodeValueNotFound() {
        when(officeRepositoryWrapper.findOneWithNotFoundDetection(1L)).thenReturn(mock(Office.class));
        when(codeValueRepository.findById(1L)).thenReturn(Optional.of(new CodeValue()));
        when(codeValueRepository.findById(2L)).thenReturn(Optional.empty());

        JsonCommand command = commandFrom(
                "{\"officeId\":1,\"name\":\"Tag rule\",\"debitTags\":[1],\"creditTags\":[2],\"allowMultipleDebitEntries\":false,"
                        + "\"allowMultipleCreditEntries\":false}");

        assertThrows(CodeValueNotFoundException.class, () -> service.createAccountingRule(command));
    }

    @Test
    void createWithDuplicateNameMapsUniqueConstraintMessageToRuleDuplicate() {
        when(officeRepositoryWrapper.findOneWithNotFoundDetection(1L)).thenReturn(mock(Office.class));
        when(accountRepositoryWrapper.findOneWithNotFoundDetection(10L)).thenReturn(new GLAccount());
        when(accountRepositoryWrapper.findOneWithNotFoundDetection(11L)).thenReturn(new GLAccount());
        when(accountingRuleRepository.saveAndFlush(any(AccountingRule.class)))
                .thenThrow(new DataIntegrityViolationException("violates accounting_rule_name_unique"));

        JsonCommand command = commandFrom("{\"officeId\":1,\"name\":\"Dup\",\"accountToDebit\":10,\"accountToCredit\":11}");

        AccountingRuleDuplicateException ex = assertThrows(AccountingRuleDuplicateException.class,
                () -> service.createAccountingRule(command));
        assertEquals("error.msg.accounting.rule.duplicate", ex.getGlobalisationMessageCode());
    }

    @Test
    void createWithDuplicateTagMappingMapsToTheTagDuplicateVariant() {
        when(officeRepositoryWrapper.findOneWithNotFoundDetection(1L)).thenReturn(mock(Office.class));
        when(codeValueRepository.findById(1L)).thenReturn(Optional.of(new CodeValue()));
        when(codeValueRepository.findById(2L)).thenReturn(Optional.of(new CodeValue()));
        when(accountingRuleRepository.saveAndFlush(any(AccountingRule.class)))
                .thenThrow(new DataIntegrityViolationException("violates UNIQUE_ACCOUNT_RULE_TAGS"));

        JsonCommand command = commandFrom(
                "{\"officeId\":1,\"name\":\"Tag rule\",\"debitTags\":[1],\"creditTags\":[2],\"allowMultipleDebitEntries\":false,"
                        + "\"allowMultipleCreditEntries\":false}");

        AccountingRuleDuplicateException ex = assertThrows(AccountingRuleDuplicateException.class,
                () -> service.createAccountingRule(command));
        assertEquals("error.msg.accounting.rule.tag.duplicate", ex.getGlobalisationMessageCode());
    }

    @Test
    void updateWithBothDebitAccountAndDebitTagsIsRejected() {
        JsonCommand command = commandFrom("{\"accountToDebit\":10,\"debitTags\":[1],\"accountToCredit\":11}");

        AccountingRuleDataException ex = assertThrows(AccountingRuleDataException.class,
                () -> service.updateAccountingRule(7L, command));
        assertEquals("error.msg.accounting.rule.accountToDebit.or.debitTags.required", ex.getGlobalisationMessageCode());
    }

    @Test
    void deleteRemovesTheRuleWithNoUsageOrTransactionGuardsAtAll() {
        // CHARACTERIZED (suspect — candidate for S2 decision): unlike deleteGLAccount (three guards),
        // deleteAccountingRule performs NO journal-entry / usage / system-defined check before deleting.
        AccountingRule rule = new AccountingRule();
        rule.setId(7L);
        when(accountingRuleRepositoryWrapper.findOneWithNotFoundDetection(7L)).thenReturn(rule);

        CommandProcessingResult result = service.deleteAccountingRule(7L);

        assertEquals(7L, result.getResourceId());
        verify(accountingRuleRepository, times(1)).delete(rule);
    }
}
