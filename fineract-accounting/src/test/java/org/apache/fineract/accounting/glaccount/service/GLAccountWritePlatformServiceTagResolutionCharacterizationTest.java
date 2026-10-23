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
package org.apache.fineract.accounting.glaccount.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.accounting.common.AccountingConstants;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
import org.apache.fineract.accounting.glaccount.serialization.GLAccountCommandFromApiJsonDeserializer;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryRepository;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.infrastructure.codes.domain.CodeValue;
import org.apache.fineract.infrastructure.codes.domain.CodeValueRepositoryWrapper;
import org.apache.fineract.infrastructure.codes.exception.CodeValueNotFoundException;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S1 characterization suite, DA cycle-2 addition (MC 11150, finding F-2): pins the tag-resolution path
 * (retrieveTagId) of {@link GLAccountWritePlatformServiceJpaRepositoryImpl} at pin 355287c8 — the code-name
 * dispatch by account type and the service-level consequences of tag values the command validation lets pass.
 */
@ExtendWith(MockitoExtension.class)
class GLAccountWritePlatformServiceTagResolutionCharacterizationTest {

    private static final Long GL_ACCOUNT_ID = 42L;

    @Mock
    private GLAccountRepository glAccountRepository;
    @Mock
    private JournalEntryRepository glJournalEntryRepository;
    @Mock
    private ProductToGLAccountMappingRepository productToGLAccountMappingRepository;
    @Mock
    private CodeValueRepositoryWrapper codeValueRepositoryWrapper;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private final FromJsonHelper fromJsonHelper = new FromJsonHelper();

    private GLAccountWritePlatformServiceJpaRepositoryImpl service;

    @BeforeEach
    void setUp() {
        service = new GLAccountWritePlatformServiceJpaRepositoryImpl(glAccountRepository, glJournalEntryRepository,
                productToGLAccountMappingRepository, new GLAccountCommandFromApiJsonDeserializer(fromJsonHelper),
                codeValueRepositoryWrapper, jdbcTemplate);
    }

    private JsonCommand commandFrom(String json) {
        return JsonCommand.from(json, fromJsonHelper.parse(json), fromJsonHelper, "glAccount", GL_ACCOUNT_ID, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void createDispatchesTheTagLookupToTheCodeNameMatchingTheAccountType() {
        // one branch per GLAccountType; verifying the exact code-name string per type makes any
        // dispatch-mutation (wrong constant, swapped branch) fail this pin
        final String[] codeNames = { AccountingConstants.ASSESTS_TAG_OPTION_CODE_NAME,
                AccountingConstants.LIABILITIES_TAG_OPTION_CODE_NAME, AccountingConstants.EQUITY_TAG_OPTION_CODE_NAME,
                AccountingConstants.INCOME_TAG_OPTION_CODE_NAME, AccountingConstants.EXPENSES_TAG_OPTION_CODE_NAME };

        when(glAccountRepository.saveAndFlush(any(GLAccount.class))).thenAnswer(invocation -> {
            GLAccount account = invocation.getArgument(0);
            account.setId(999L);
            return account;
        });

        for (int type = 1; type <= 5; type++) {
            long tagId = 70L + type;
            when(codeValueRepositoryWrapper.findOneByCodeNameAndIdWithNotFoundDetection(codeNames[type - 1], tagId))
                    .thenReturn(new CodeValue());

            JsonCommand command = commandFrom(
                    "{\"name\":\"Tagged " + type + "\",\"glCode\":\"T00" + type + "\",\"type\":" + type + ",\"usage\":1,"
                            + "\"manualEntriesAllowed\":true,\"tagId\":" + tagId + "}");

            CommandProcessingResult result = service.createGLAccount(command);

            assertEquals(999L, result.getResourceId());
            verify(codeValueRepositoryWrapper).findOneByCodeNameAndIdWithNotFoundDetection(codeNames[type - 1], tagId);
        }
    }

    @Test
    void createPropagatesTheWrappersNotFoundFailureForAnUnknownTagUncaught() {
        // create's data-integrity catch covers JpaSystem/DataAccess only — a CodeValueNotFoundException from
        // the wrapper's not-found detection passes through the create path untouched (pinned propagation)
        when(codeValueRepositoryWrapper.findOneByCodeNameAndIdWithNotFoundDetection(
                AccountingConstants.ASSESTS_TAG_OPTION_CODE_NAME, 7L)).thenThrow(new CodeValueNotFoundException(7L));

        JsonCommand command = commandFrom("{\"name\":\"Tagged\",\"glCode\":\"T1\",\"type\":1,\"usage\":1,"
                + "\"manualEntriesAllowed\":true,\"tagId\":7}");

        assertThrows(CodeValueNotFoundException.class, () -> service.createGLAccount(command));
        verify(glAccountRepository, never()).saveAndFlush(any(GLAccount.class));
    }

    @Test
    void updateWithNegativeTagIdPassesCommandValidationThenFailsAtTheServiceTagLookup() {
        // CHARACTERIZED (suspect — candidate for S2 decision): chain of §6.1 — validateForUpdate's dead trailing
        // TAGID check lets tagId=-1 through the command layer; only now does the service resolve it and the
        // wrapper's not-found detection surface it, as CodeValueNotFoundException(-1) — the user sees a code-value
        // error for a value the API validator should have rejected with a proper validation message.
        GLAccount existing = new GLAccount();
        existing.setId(GL_ACCOUNT_ID);
        existing.setName("Cash");
        existing.setGlCode("1001");
        existing.setType(1);
        existing.setUsage(1);
        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(java.util.Optional.of(existing));
        when(codeValueRepositoryWrapper.findOneByCodeNameAndIdWithNotFoundDetection(
                AccountingConstants.ASSESTS_TAG_OPTION_CODE_NAME, -1L)).thenThrow(new CodeValueNotFoundException(-1L));

        JsonCommand command = commandFrom("{\"name\":\"Cash2\",\"tagId\":-1}");

        assertThrows(CodeValueNotFoundException.class, () -> service.updateGLAccount(GL_ACCOUNT_ID, command));
    }

    @Test
    void updateWithTagIdZeroIsSilentlyDroppedAsNoChange() {
        // CHARACTERIZED (suspect — candidate for S2 decision): GLAccount.update treats an absent tag entity as
        // base 0L, so an explicit tagId=0 update is indistinguishable from "no tag change": no wrapper lookup,
        // no reported change — the requested (invalid anyway) value vanishes without any error anywhere.
        GLAccount existing = new GLAccount();
        existing.setId(GL_ACCOUNT_ID);
        existing.setName("Cash");
        existing.setGlCode("1001");
        existing.setType(1);
        existing.setUsage(1);
        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(java.util.Optional.of(existing));
        when(glAccountRepository.saveAndFlush(any(GLAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JsonCommand command = commandFrom("{\"name\":\"Cash2\",\"tagId\":0}");

        CommandProcessingResult result = service.updateGLAccount(GL_ACCOUNT_ID, command);

        assertTrue(result.getChanges().containsKey("name"));
        assertFalse(result.getChanges().containsKey("tagId"));
        verify(codeValueRepositoryWrapper, never()).findOneByCodeNameAndIdWithNotFoundDetection(anyString(), any(Long.class));
    }
}
