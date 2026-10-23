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
package org.apache.fineract.accounting.provisioning.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.provisioning.data.LoanProductProvisioningEntryData;
import org.apache.fineract.accounting.provisioning.data.ProvisioningEntryData;
import org.apache.fineract.accounting.provisioning.domain.ProvisioningEntriesReadRepository;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Slf4j
@Service
@ConditionalOnMissingBean(value = ProvisioningEntriesReadPlatformService.class, ignored = ProvisioningEntriesReadPlatformServiceImpl.class)
public class ProvisioningEntriesReadPlatformServiceImpl implements ProvisioningEntriesReadPlatformService {

    // S4c peel (MC 11183, S4-T converge leg): the six dotted SQL sites — AND the two PaginationHelper
    // hand-offs (the named residual: the JdbcTemplate instance now lives in the repository only for the
    // core PaginationHelper fetchPage calls) — moved byte-identical into ProvisioningEntriesReadRepository
    // on AccountingNativeSqlExecutor. The service holds no SQL and no JDBC collaborator.
    private final ProvisioningEntriesReadRepository provisioningEntriesReadRepository;

    @Override
    public Collection<LoanProductProvisioningEntryData> retrieveLoanProductsProvisioningData(LocalDate date) {
        return this.provisioningEntriesReadRepository.loanProductsProvisioningData(date);
    }

    @Override
    public ProvisioningEntryData retrieveProvisioningEntryData(Long entryId) {
        return this.provisioningEntriesReadRepository.provisioningEntryDataById(entryId);
    }

    @Override
    public Page<ProvisioningEntryData> retrieveAllProvisioningEntries(Integer offset, Integer limit) {
        return this.provisioningEntriesReadRepository.allProvisioningEntries(offset, limit);
    }

    @Override
    public ProvisioningEntryData retrieveProvisioningEntryData(String date) {
        ProvisioningEntryData data = null;
        try {
            data = this.provisioningEntriesReadRepository.provisioningEntryDataByCreatedDateLike(date);
        } catch (EmptyResultDataAccessException e) {
            log.error("Problem occurred in retrieveProvisioningEntryData function", e);
        }

        return data;
    }

    @Override
    public ProvisioningEntryData retrieveProvisioningEntryDataByCriteriaId(Long criteriaId) {
        ProvisioningEntryData data = null;
        Collection<LoanProductProvisioningEntryData> entries = this.provisioningEntriesReadRepository
                .loanProductProvisioningEntriesByCriteriaId(criteriaId); // NOSONAR
        if (entries != null && !entries.isEmpty()) {
            Long entryId = ((LoanProductProvisioningEntryData) entries.toArray()[0]).getHistoryId();
            data = this.provisioningEntriesReadRepository.provisioningEntryById(entryId); // NOSONAR
            data.setProvisioningEntries(entries);
        }
        return data;
    }

    @Override
    public ProvisioningEntryData retrieveExistingProvisioningIdDateWithJournals() {
        ProvisioningEntryData data = null;
        try {
            data = this.provisioningEntriesReadRepository.existingProvisioningIdDateWithJournals();
        } catch (EmptyResultDataAccessException e) {
            data = null;
        }
        return data;
    }

    @Override
    public Page<LoanProductProvisioningEntryData> retrieveProvisioningEntries(SearchParameters searchParams) {
        return this.provisioningEntriesReadRepository.provisioningEntries(searchParams);
    }

}
