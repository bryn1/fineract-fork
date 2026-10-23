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

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.glaccount.data.GLAccountData;
import org.apache.fineract.accounting.glaccount.data.GLAccountDataForLookup;
import org.apache.fineract.accounting.glaccount.domain.GLAccountReadRepository;
import org.apache.fineract.accounting.glaccount.domain.GLAccountType;
import org.apache.fineract.accounting.glaccount.domain.GLAccountUsage;
import org.apache.fineract.accounting.glaccount.exception.GLAccountInvalidClassificationException;
import org.apache.fineract.accounting.glaccount.exception.GLAccountNotFoundException;
import org.apache.fineract.accounting.journalentry.data.JournalEntryAssociationParametersData;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GLAccountReadPlatformServiceImpl implements GLAccountReadPlatformService {

    private final GLAccountReadRepository glAccountReadRepository;

    @Override
    public List<GLAccountData> retrieveAllGLAccounts(final Integer accountClassification, final String searchParam, final Integer usage,
            final Boolean manualTransactionsAllowed, final Boolean disabled,
            JournalEntryAssociationParametersData associationParametersData) {
        if (accountClassification != null && !checkValidGLAccountType(accountClassification)) {
            throw new GLAccountInvalidClassificationException(accountClassification);
        }

        if (usage != null && !checkValidGLAccountUsage(usage)) {
            throw new GLAccountInvalidClassificationException(accountClassification);
        }

        return this.glAccountReadRepository.retrieveAllGLAccounts(accountClassification, searchParam, usage, manualTransactionsAllowed,
                disabled, associationParametersData);
    }

    @Override
    public GLAccountData retrieveGLAccountById(final long glAccountId, JournalEntryAssociationParametersData associationParametersData) {
        try {
            return this.glAccountReadRepository.retrieveGLAccountById(glAccountId, associationParametersData);
        } catch (final EmptyResultDataAccessException e) {
            throw new GLAccountNotFoundException(glAccountId, e);
        }
    }

    @Override
    public List<GLAccountData> retrieveAllEnabledDetailGLAccounts(final GLAccountType accountType) {
        return retrieveAllGLAccounts(accountType.getValue(), null, GLAccountUsage.DETAIL.getValue(), null, false,
                new JournalEntryAssociationParametersData());
    }

    @Override
    public List<GLAccountData> retrieveAllEnabledDetailGLAccounts() {
        return retrieveAllGLAccounts(null, null, GLAccountUsage.DETAIL.getValue(), null, false,
                new JournalEntryAssociationParametersData());
    }

    private static boolean checkValidGLAccountType(final int type) {
        for (final GLAccountType accountType : GLAccountType.values()) {
            if (accountType.getValue().equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkValidGLAccountUsage(final int type) {
        for (final GLAccountUsage accountUsage : GLAccountUsage.values()) {
            if (accountUsage.getValue().equals(type)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public GLAccountData retrieveNewGLAccountDetails(final Integer type) {
        return GLAccountData.sensibleDefaultsForNewGLAccountCreation(type);
    }

    @Override
    public List<GLAccountData> retrieveAllEnabledHeaderGLAccounts(final GLAccountType accountType) {
        return retrieveAllGLAccounts(accountType.getValue(), null, GLAccountUsage.HEADER.getValue(), null, false,
                new JournalEntryAssociationParametersData());
    }

    @Override
    public List<GLAccountDataForLookup> retrieveAccountsByTagId(final Long ruleId, final Integer transactionType) {
        return this.glAccountReadRepository.retrieveAccountsByTagId(ruleId, transactionType);
    }
}
