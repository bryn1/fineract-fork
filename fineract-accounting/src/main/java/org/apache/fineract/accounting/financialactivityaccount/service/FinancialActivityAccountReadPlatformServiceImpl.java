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
package org.apache.fineract.accounting.financialactivityaccount.service;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.accounting.common.AccountingDropdownReadPlatformService;
import org.apache.fineract.accounting.financialactivityaccount.data.FinancialActivityAccountData;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccountReadRepository;
import org.apache.fineract.accounting.financialactivityaccount.exception.FinancialActivityAccountNotFoundException;
import org.apache.fineract.accounting.glaccount.data.GLAccountData;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FinancialActivityAccountReadPlatformServiceImpl implements FinancialActivityAccountReadPlatformService {

    // S4c peel (MC 11183): SQL execution moved byte-identical into FinancialActivityAccountReadRepository
    // behind AccountingNativeSqlExecutor (S4-T converge leg).
    private final FinancialActivityAccountReadRepository financialActivityAccountReadRepository;
    private final AccountingDropdownReadPlatformService accountingDropdownReadPlatformService;

    @Override
    public List<FinancialActivityAccountData> retrieveAll() {
        return this.financialActivityAccountReadRepository.retrieveAll();
    }

    @Override
    public FinancialActivityAccountData retrieve(Long financialActivityAccountId) {
        try {
            return this.financialActivityAccountReadRepository.retrieve(financialActivityAccountId);
        } catch (final EmptyResultDataAccessException e) {
            throw new FinancialActivityAccountNotFoundException(financialActivityAccountId, e);
        }
    }

    @Override
    public FinancialActivityAccountData addTemplateDetails(FinancialActivityAccountData financialActivityAccountData) {
        final Map<String, List<GLAccountData>> accountOptions = this.accountingDropdownReadPlatformService.retrieveAccountMappingOptions();
        financialActivityAccountData.setGlAccountOptions(accountOptions);
        financialActivityAccountData.setFinancialActivityOptions(FinancialActivity.getAllFinancialActivities());
        return financialActivityAccountData;
    }

    @Override
    public FinancialActivityAccountData getFinancialActivityAccountTemplate() {
        FinancialActivityAccountData financialActivityAccountData = new FinancialActivityAccountData();
        return addTemplateDetails(financialActivityAccountData);
    }

}
