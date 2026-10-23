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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
import org.apache.fineract.accounting.glaccount.exception.GLAccountDisableException;
import org.apache.fineract.accounting.glaccount.exception.GLAccountInvalidDeleteException;
import org.apache.fineract.accounting.glaccount.exception.GLAccountInvalidUpdateException;
import org.apache.fineract.accounting.glaccount.exception.GLAccountNotFoundException;
import org.apache.fineract.accounting.glaccount.exception.InvalidParentGLAccountHeadException;
import org.apache.fineract.accounting.glaccount.serialization.GLAccountCommandFromApiJsonDeserializer;
import org.apache.fineract.accounting.journalentry.domain.JournalEntry;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryRepository;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMapping;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.infrastructure.codes.domain.CodeValueRepositoryWrapper;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S1 characterization suite (MC 11150): pins update/delete-path behavior of
 * {@link GLAccountWritePlatformServiceJpaRepositoryImpl} at pin 355287c8 (unit-level, Mockito, DB-free).
 * The delete HAPPY path is already pinned by GLAccountWritePlatformServiceJpaRepositoryImplTest at this pin.
 */
@ExtendWith(MockitoExtension.class)
class GLAccountWritePlatformServiceUpdateDeleteCharacterizationTest {

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

    private GLAccount existingAccount(int usage) {
        GLAccount account = new GLAccount();
        account.setId(GL_ACCOUNT_ID);
        account.setName("Cash");
        account.setGlCode("1001");
        account.setUsage(usage);
        account.setType(1);
        return account;
    }

    @Test
    void updateRejectsParentIdEqualToOwnIdBeforeAnyLookup() {
        JsonCommand command = commandFrom("{\"parentId\":42}");

        InvalidParentGLAccountHeadException ex = assertThrows(InvalidParentGLAccountHeadException.class,
                () -> service.updateGLAccount(GL_ACCOUNT_ID, command));
        assertEquals("error.msg.glaccount.id.and.parentid.must.not.same", ex.getGlobalisationMessageCode());
        verify(glAccountRepository, never()).findById(any());
    }

    @Test
    void updateOfMissingAccountFailsWithNotFound() {
        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(Optional.empty());

        JsonCommand command = commandFrom("{\"name\":\"Renamed\"}");
        assertThrows(GLAccountNotFoundException.class, () -> service.updateGLAccount(GL_ACCOUNT_ID, command));
    }

    @Test
    void updateWithoutAnyActualChangeDoesNotFlushAndReportsNullChanges() {
        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(Optional.of(existingAccount(1)));

        JsonCommand command = commandFrom("{\"name\":\"Cash\"}");
        CommandProcessingResult result = service.updateGLAccount(GL_ACCOUNT_ID, command);

        assertEquals(GL_ACCOUNT_ID, result.getResourceId());
        // CommandProcessingResult.getChanges() intentionally collapses an empty map to NULL (by design in core,
        // pinned here so S2 keeps the JSON contract: a no-op update serializes WITHOUT a changes field).
        assertFalse(result.hasChanges());
        assertNull(result.getChanges());
        verify(glAccountRepository, never()).saveAndFlush(any(GLAccount.class));
    }

    @Test
    void usageOnlyUpdateReachesTheServiceUsageGuard() {
        // S2 FIX (MC 11153, suspect 6.5): validateForUpdate's anyOfNotNull guard now covers every updatable
        // parameter (usage and tagId were missing), so a usage-only PUT reaches the service-level usage
        // guard — DETAIL→HEADER promotion with transactions logged is rejected there (previously the PUT
        // died at the command layer with "No parameters passed for update." and the guard never ran).
        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(Optional.of(existingAccount(1))); // DETAIL today
        when(glJournalEntryRepository.exists(ArgumentMatchers.<Specification<JournalEntry>>any())).thenReturn(true);

        JsonCommand command = commandFrom("{\"usage\":2}");
        GLAccountInvalidUpdateException ex = assertThrows(GLAccountInvalidUpdateException.class,
                () -> service.updateGLAccount(GL_ACCOUNT_ID, command));
        assertEquals("error.msg.glaccount.glcode.invalid.update.transactions.logged", ex.getGlobalisationMessageCode());
    }

    @Test
    void manualEntriesAllowedOnlyUpdateReachesTheServiceAndIsApplied() {
        // S2 FIX (MC 11153, suspect 6.5 — DA-gate completion of the guard set): manualEntriesAllowed is likewise
        // an updatable property (applied and reported by GLAccount.update) yet trailed the guard set too, so a
        // manualEntriesAllowed-only PUT still died at "No parameters passed for update."; the guard now covers
        // exactly the nine updatable parameters.
        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(Optional.of(existingAccount(1)));
        when(glAccountRepository.saveAndFlush(any(GLAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JsonCommand command = commandFrom("{\"manualEntriesAllowed\":false}");

        CommandProcessingResult result = service.updateGLAccount(GL_ACCOUNT_ID, command);

        assertEquals(false, result.getChanges().get("manualEntriesAllowed"));
        verify(glAccountRepository).saveAndFlush(any(GLAccount.class));
    }

    @Test
    void changingUsageToHeaderTogetherWithANameChangeWhenJournalEntriesExistIsRejected() {
        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(Optional.of(existingAccount(1))); // DETAIL today
        when(glJournalEntryRepository.exists(ArgumentMatchers.<Specification<JournalEntry>>any())).thenReturn(true);

        JsonCommand command = commandFrom("{\"name\":\"Cash2\",\"usage\":2}");
        GLAccountInvalidUpdateException ex = assertThrows(GLAccountInvalidUpdateException.class,
                () -> service.updateGLAccount(GL_ACCOUNT_ID, command));
        assertEquals("error.msg.glaccount.glcode.invalid.update.transactions.logged", ex.getGlobalisationMessageCode());
    }

    @Test
    void disablingAnAccountAttachedToAProductIsRejected() {
        // CHARACTERIZED: the disable guard runs a raw COUNT on acc_product_mapping through JdbcTemplate
        // BEFORE the account is even loaded — pinned as current shape; S2 decides whether it belongs in a validator.
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);

        JsonCommand command = commandFrom("{\"disabled\":true}");
        GLAccountDisableException ex = assertThrows(GLAccountDisableException.class,
                () -> service.updateGLAccount(GL_ACCOUNT_ID, command));
        assertEquals("error.msg.glaccount.attached.to.product", ex.getGlobalisationMessageCode());
    }

    @Test
    void deleteOfMissingAccountFailsWithNotFound() {
        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(Optional.empty());
        assertThrows(GLAccountNotFoundException.class, () -> service.deleteGLAccount(GL_ACCOUNT_ID));
    }

    @Test
    void deleteOfHeaderAccountWithChildrenIsRejected() {
        GLAccount header = existingAccount(2); // HEADER
        header.setChildren(List.of(new GLAccount()));
        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(Optional.of(header));

        GLAccountInvalidDeleteException ex = assertThrows(GLAccountInvalidDeleteException.class,
                () -> service.deleteGLAccount(GL_ACCOUNT_ID));
        assertEquals("error.msg.glaccount.glcode.invalid.delete.has.children", ex.getGlobalisationMessageCode());
    }

    @Test
    void deleteOfAccountWithJournalEntriesIsRejected() {
        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(Optional.of(existingAccount(1)));
        when(glJournalEntryRepository.exists(ArgumentMatchers.<Specification<JournalEntry>>any())).thenReturn(true);

        GLAccountInvalidDeleteException ex = assertThrows(GLAccountInvalidDeleteException.class,
                () -> service.deleteGLAccount(GL_ACCOUNT_ID));
        assertEquals("error.msg.glaccount.glcode.invalid.delete.transactions.logged", ex.getGlobalisationMessageCode());
    }

    @Test
    void deleteOfAccountWithProductMappingIsRejected() {
        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(Optional.of(existingAccount(1)));
        when(glJournalEntryRepository.exists(ArgumentMatchers.<Specification<JournalEntry>>any())).thenReturn(false);
        when(productToGLAccountMappingRepository.exists(ArgumentMatchers.<Specification<ProductToGLAccountMapping>>any())).thenReturn(true);

        GLAccountInvalidDeleteException ex = assertThrows(GLAccountInvalidDeleteException.class,
                () -> service.deleteGLAccount(GL_ACCOUNT_ID));
        assertEquals("error.msg.glaccount.glcode.invalid.delete.product.mapping", ex.getGlobalisationMessageCode());
    }

    @Test
    void deleteOfHeaderAccountWithoutChildrenSkipsTheChildrenGuardAndSucceeds() {
        // CHARACTERIZED (suspect — candidate for S2 decision): the children guard only fires for HEADER accounts
        // WITH children; a header account whose children collection is empty (e.g. detached/never loaded)
        // deletes without any further check beyond journal-entries/product-mapping.
        GLAccount header = existingAccount(2);
        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(Optional.of(header));
        when(glJournalEntryRepository.exists(ArgumentMatchers.<Specification<JournalEntry>>any())).thenReturn(false);
        when(productToGLAccountMappingRepository.exists(ArgumentMatchers.<Specification<ProductToGLAccountMapping>>any())).thenReturn(false);

        CommandProcessingResult result = service.deleteGLAccount(GL_ACCOUNT_ID);

        assertEquals(GL_ACCOUNT_ID, result.getResourceId());
        verify(glAccountRepository).delete(header);
    }

    @Test
    void demotingHeaderToDetailWithChildrenAndTransactionsIsRejected() {
        // S2 FIX (MC 11153, suspect 6.6): the usage-change guard is now two-directional — a HEADER→DETAIL
        // demotion while child accounts exist or transactions are logged against the account throws
        // GLAccountInvalidUpdateException(HEADER_DEMOTED_WITH_CHILDREN_OR_TRANSACTIONS) before any save.
        // (D-2 resolved alongside: the dead GLAccountInvalidUsageException class was deleted — its stated
        // "invalid COA type" concern is owned by GLAccountInvalidClassificationException, and the update-
        // guard concern by this exception's reason enum. One implementation per concern.)
        GLAccount header = existingAccount(2); // HEADER today
        header.setChildren(List.of(new GLAccount()));
        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(Optional.of(header));
        when(glJournalEntryRepository.exists(ArgumentMatchers.<Specification<JournalEntry>>any())).thenReturn(true);

        JsonCommand command = commandFrom("{\"name\":\"Renamed\",\"usage\":1}"); // → DETAIL

        GLAccountInvalidUpdateException ex = assertThrows(GLAccountInvalidUpdateException.class,
                () -> service.updateGLAccount(GL_ACCOUNT_ID, command));
        assertEquals("error.msg.glaccount.glcode.invalid.update.header.demotion", ex.getGlobalisationMessageCode());
        verify(glAccountRepository, never()).saveAndFlush(any(GLAccount.class));
    }

    @Test
    void demotingHeaderToDetailWithoutChildrenOrTransactionsIsAllowed() {
        // positive control for the new demotion guard: an empty header (no children, no journal entries)
        // demotes normally — the guard fires on the structural danger, not on demotion per se
        GLAccount header = existingAccount(2); // HEADER today
        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(Optional.of(header));
        when(glJournalEntryRepository.exists(ArgumentMatchers.<Specification<JournalEntry>>any())).thenReturn(false);
        when(glAccountRepository.saveAndFlush(any(GLAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JsonCommand command = commandFrom("{\"name\":\"Renamed\",\"usage\":1}"); // → DETAIL

        CommandProcessingResult result = service.updateGLAccount(GL_ACCOUNT_ID, command);

        assertEquals(1, result.getChanges().get("usage"));
        verify(glAccountRepository).saveAndFlush(header);
    }
}
