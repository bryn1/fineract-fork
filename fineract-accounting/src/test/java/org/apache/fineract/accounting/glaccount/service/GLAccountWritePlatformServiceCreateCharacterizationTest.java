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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
import org.apache.fineract.accounting.glaccount.exception.GLAccountDuplicateException;
import org.apache.fineract.accounting.glaccount.exception.GLAccountInvalidParentException;
import org.apache.fineract.accounting.glaccount.exception.GLAccountNotFoundException;
import org.apache.fineract.accounting.glaccount.serialization.GLAccountCommandFromApiJsonDeserializer;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryRepository;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.infrastructure.codes.domain.CodeValueRepositoryWrapper;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * S1 characterization suite (MC 11150): pins create-path behavior of
 * {@link GLAccountWritePlatformServiceJpaRepositoryImpl} at pin 355287c8 (unit-level, Mockito, DB-free).
 */
@ExtendWith(MockitoExtension.class)
class GLAccountWritePlatformServiceCreateCharacterizationTest {

    @Mock
    private GLAccountRepository glAccountRepository;
    @Mock
    private JournalEntryRepository glJournalEntryRepository;
    @Mock
    private ProductToGLAccountMappingRepository productToGLAccountMappingRepository;
    @Mock
    private CodeValueRepositoryWrapper codeValueRepositoryWrapper;

    private final FromJsonHelper fromJsonHelper = new FromJsonHelper();

    private GLAccountWritePlatformServiceJpaRepositoryImpl service;

    @BeforeEach
    void setUp() {
        // real deserializer on purpose: pins the create path as composed today (JSON -> command -> entity)
        service = new GLAccountWritePlatformServiceJpaRepositoryImpl(glAccountRepository, glJournalEntryRepository,
                productToGLAccountMappingRepository, new GLAccountCommandFromApiJsonDeserializer(fromJsonHelper),
                codeValueRepositoryWrapper);
    }

    private JsonCommand commandFrom(String json) {
        return JsonCommand.from(json, fromJsonHelper.parse(json), fromJsonHelper, "glAccount", null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @Test
    void createHappyPathPersistsTwiceAndReturnsTheGeneratedId() {
        // CHARACTERIZED: create saves the account, generates hierarchy, and saveAndFlushes AGAIN (two flushes
        // per create) — pinned as current behavior; S2 decides whether to collapse it.
        when(glAccountRepository.saveAndFlush(any(GLAccount.class))).thenAnswer(invocation -> {
            GLAccount account = invocation.getArgument(0);
            account.setId(99L);
            return account;
        });

        JsonCommand command = commandFrom(
                "{\"name\":\"Cash\",\"glCode\":\"1001\",\"type\":1,\"usage\":1,\"manualEntriesAllowed\":true}");

        CommandProcessingResult result = service.createGLAccount(command);

        assertEquals(99L, result.getResourceId());
        verify(glAccountRepository, times(2)).saveAndFlush(any(GLAccount.class));
    }

    @Test
    void createMapsUniqueGlCodeViolationToGLAccountDuplicateExceptionCarryingTheCode() {
        when(glAccountRepository.saveAndFlush(any(GLAccount.class)))
                .thenThrow(new DataIntegrityViolationException("Batch entry failed on constraint 'acc_gl_code'"));

        JsonCommand command = commandFrom(
                "{\"name\":\"Cash\",\"glCode\":\"DUP\",\"type\":1,\"usage\":1,\"manualEntriesAllowed\":true}");

        GLAccountDuplicateException ex = assertThrows(GLAccountDuplicateException.class, () -> service.createGLAccount(command));
        // the duplicate exception is raised purely by substring-matching the DB message on "acc_gl_code"
        assertEquals("error.msg.glaccount.glcode.duplicate", ex.getGlobalisationMessageCode());
        assertEquals("DUP", ex.getDefaultUserMessageArgs()[0]);
    }

    @Test
    void createMapsAnyOtherIntegrityViolationToTheUnknownDataIntegrityCode() {
        when(glAccountRepository.saveAndFlush(any(GLAccount.class)))
                .thenThrow(new DataIntegrityViolationException("fk acc_gl_account_parent"));

        JsonCommand command = commandFrom(
                "{\"name\":\"Cash\",\"glCode\":\"1001\",\"type\":1,\"usage\":1,\"manualEntriesAllowed\":true}");

        PlatformDataIntegrityException ex = assertThrows(PlatformDataIntegrityException.class, () -> service.createGLAccount(command));
        assertEquals("error.msg.glAccount.unknown.data.integrity.issue", ex.getGlobalisationMessageCode());
    }

    @Test
    void createWithUnknownParentFailsWithNotFoundForTheParentId() {
        when(glAccountRepository.findById(5L)).thenReturn(Optional.empty());

        JsonCommand command = commandFrom(
                "{\"name\":\"Child\",\"glCode\":\"1002\",\"type\":1,\"usage\":1,\"manualEntriesAllowed\":true,\"parentId\":5}");

        GLAccountNotFoundException ex = assertThrows(GLAccountNotFoundException.class, () -> service.createGLAccount(command));
        // message args name the PARENT id, not the (nonexistent) new account
        assertEquals("error.msg.glaccount.id.invalid", ex.getGlobalisationMessageCode());
    }

    @Test
    void createWithDetailAccountAsParentIsRejected() {
        GLAccount detailParent = new GLAccount();
        detailParent.setId(5L);
        detailParent.setUsage(1); // GLAccountUsage.DETAIL
        when(glAccountRepository.findById(5L)).thenReturn(Optional.of(detailParent));

        JsonCommand command = commandFrom(
                "{\"name\":\"Child\",\"glCode\":\"1002\",\"type\":1,\"usage\":1,\"manualEntriesAllowed\":true,\"parentId\":5}");

        GLAccountInvalidParentException ex = assertThrows(GLAccountInvalidParentException.class, () -> service.createGLAccount(command));
        assertEquals("error.msg.glaccount.parent.invalid", ex.getGlobalisationMessageCode());
    }

    @Test
    void createRejectsUnsupportedJsonBeforeTouchingAnyRepository() {
        // no stubbing at all: pins that the JSON boundary aborts first, no repository traffic
        JsonCommand command = commandFrom("{\"name\":\"Cash\",\"unknown\":true}");
        assertThrows(RuntimeException.class, () -> service.createGLAccount(command));
        verify(glAccountRepository, times(0)).saveAndFlush(any(GLAccount.class));
        verify(glAccountRepository, times(0)).findById(eq(5L));
    }
}
