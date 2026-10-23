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

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.rule.data.AccountingRuleData;
import org.apache.fineract.accounting.rule.domain.AccountingRuleReadRepository;
import org.apache.fineract.accounting.rule.exception.AccountingRuleNotFoundException;
import org.springframework.dao.EmptyResultDataAccessException;

@RequiredArgsConstructor
public class AccountingRuleReadPlatformServiceImpl implements AccountingRuleReadPlatformService {

    private final AccountingRuleReadRepository accountingRuleReadRepository;

    @Override
    public List<AccountingRuleData> retrieveAllAccountingRules(final String hierarchySearchString,
            final boolean isAssociationParametersExists) {
        return this.accountingRuleReadRepository.retrieveAllAccountingRules(hierarchySearchString, isAssociationParametersExists);
    }

    @Override
    public AccountingRuleData retrieveAccountingRuleById(final Long accountingRuleId) {
        try {
            final Map<Long, AccountingRuleData> extractedData = this.accountingRuleReadRepository
                    .retrieveAccountingRulesMapById(accountingRuleId);
            final AccountingRuleData accountingRuleData = extractedData.get(accountingRuleId);
            if (accountingRuleData == null) {
                throw new AccountingRuleNotFoundException(accountingRuleId);
            }
            return accountingRuleData;
        } catch (final EmptyResultDataAccessException e) {
            throw new AccountingRuleNotFoundException(accountingRuleId, e);
        }
    }
}
