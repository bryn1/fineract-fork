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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.common.AccountingDropdownReadPlatformService;
import org.apache.fineract.accounting.glaccount.data.GLAccountData;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainServiceJpa;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.entityaccess.service.FineractEntityAccessUtil;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.service.CurrencyReadPlatformService;
import org.apache.fineract.portfolio.charge.data.ChargeData;
import org.apache.fineract.portfolio.charge.domain.ChargeAppliesTo;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.charge.domain.ChargeSqlReadRepository;
import org.apache.fineract.portfolio.charge.exception.ChargeNotFoundException;
import org.apache.fineract.portfolio.common.service.DropdownReadPlatformService;
import org.apache.fineract.portfolio.tax.data.TaxGroupData;
import org.apache.fineract.portfolio.tax.service.TaxReadPlatformService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author vishwas
 *
 */
@Transactional(readOnly = true)
public class ChargeReadPlatformServiceImpl implements ChargeReadPlatformService {

    private final CurrencyReadPlatformService currencyReadPlatformService;
    private final ChargeDropdownReadPlatformService chargeDropdownReadPlatformService;
    private final DropdownReadPlatformService dropdownReadPlatformService;
    private final AccountingDropdownReadPlatformService accountingDropdownReadPlatformService;
    private final TaxReadPlatformService taxReadPlatformService;
    private final ConfigurationDomainServiceJpa configurationDomainServiceJpa;
    // S4b PEEL (MC 11183): SQL moved to the charge-domain repository (research/04 S4-B row); constructor
    // signature FROZEN - the JDBC collaborators flow into the repository as well as being held here
    // during the per-site-cluster move (fields removed when the last site has delegated).
    private final ChargeSqlReadRepository chargeSqlRepository;

    public ChargeReadPlatformServiceImpl(final CurrencyReadPlatformService currencyReadPlatformService,
            final ChargeDropdownReadPlatformService chargeDropdownReadPlatformService, final JdbcTemplate jdbcTemplate,
            final DropdownReadPlatformService dropdownReadPlatformService, final FineractEntityAccessUtil fineractEntityAccessUtil,
            final AccountingDropdownReadPlatformService accountingDropdownReadPlatformService,
            final TaxReadPlatformService taxReadPlatformService, final ConfigurationDomainServiceJpa configurationDomainServiceJpa,
            final NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.currencyReadPlatformService = currencyReadPlatformService;
        this.chargeDropdownReadPlatformService = chargeDropdownReadPlatformService;
        this.dropdownReadPlatformService = dropdownReadPlatformService;
        this.accountingDropdownReadPlatformService = accountingDropdownReadPlatformService;
        this.taxReadPlatformService = taxReadPlatformService;
        this.configurationDomainServiceJpa = configurationDomainServiceJpa;
        this.chargeSqlRepository = new ChargeSqlReadRepository(jdbcTemplate, namedParameterJdbcTemplate, fineractEntityAccessUtil);
    }

    @Override
    @Cacheable(value = "charges", key = "T(org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil).getTenant().getTenantIdentifier().concat('ch')")
    public List<ChargeData> retrieveAllCharges() {
        return this.chargeSqlRepository.allCharges();
    }

    @Override
    public List<ChargeData> retrieveAllChargesForCurrency(String currencyCode) {
        return this.chargeSqlRepository.allChargesForCurrency(currencyCode);
    }

    @Override
    public ChargeData retrieveCharge(final Long chargeId) {
        try {
            return this.chargeSqlRepository.chargeById(chargeId);
        } catch (final EmptyResultDataAccessException e) {
            throw new ChargeNotFoundException(chargeId, e);
        }
    }

    @Override
    public ChargeData retrieveNewChargeDetails(Long chargeAppliesTo, Long chargeTimeType) {
        if (chargeAppliesTo != null && ChargeAppliesTo.WORKING_CAPITAL_LOAN.getValue().longValue() == chargeAppliesTo) {
            final List<EnumOptionData> allowedChargeAppliesToOptions = this.chargeDropdownReadPlatformService.retrieveApplicableToTypes();
            final Collection<CurrencyData> currencyOptions = this.currencyReadPlatformService.retrieveAllowedCurrencies();
            final List<EnumOptionData> allowedChargeTimeOptions = this.chargeDropdownReadPlatformService
                    .retrieveCollectionTimeTypes(ChargeAppliesTo.WORKING_CAPITAL_LOAN);
            final List<EnumOptionData> allowedChargeCalculationTypeOptions = this.chargeDropdownReadPlatformService
                    .retrieveCalculationTypes(ChargeAppliesTo.WORKING_CAPITAL_LOAN,
                            chargeTimeType != null ? ChargeTimeType.fromInt(chargeTimeType.intValue()) : null);
            final List<EnumOptionData> chargePaymentOptions = this.chargeDropdownReadPlatformService
                    .retrievePaymentModes(ChargeAppliesTo.WORKING_CAPITAL_LOAN);

            return ChargeData.builder().currencyOptions(currencyOptions).chargeCalculationTypeOptions(allowedChargeCalculationTypeOptions)
                    .chargeAppliesToOptions(allowedChargeAppliesToOptions).chargeTimeTypeOptions(allowedChargeTimeOptions)
                    .chargePaymetModeOptions(chargePaymentOptions).build();

        }
        return retrieveNewChargeDetails();
    }

    @Override
    public ChargeData retrieveNewChargeDetails() {
        final Collection<CurrencyData> currencyOptions = this.currencyReadPlatformService.retrieveAllowedCurrencies();
        final List<EnumOptionData> allowedChargeCalculationTypeOptions = this.chargeDropdownReadPlatformService.retrieveCalculationTypes();
        final List<EnumOptionData> allowedChargeAppliesToOptions = this.chargeDropdownReadPlatformService.retrieveApplicableToTypes();
        final List<EnumOptionData> allowedChargeTimeOptions = this.chargeDropdownReadPlatformService.retrieveCollectionTimeTypes();
        final List<EnumOptionData> chargePaymentOptions = this.chargeDropdownReadPlatformService.retrievePaymentModes();
        final List<EnumOptionData> loansChargeCalculationTypeOptions = this.chargeDropdownReadPlatformService
                .retrieveLoanCalculationTypes();
        final List<EnumOptionData> loansChargeTimeTypeOptions = this.chargeDropdownReadPlatformService.retrieveLoanCollectionTimeTypes();
        final List<EnumOptionData> savingsChargeCalculationTypeOptions = this.chargeDropdownReadPlatformService
                .retrieveSavingsCalculationTypes();
        final List<EnumOptionData> savingsChargeTimeTypeOptions = this.chargeDropdownReadPlatformService
                .retrieveSavingsCollectionTimeTypes();
        final List<EnumOptionData> clientChargeCalculationTypeOptions = this.chargeDropdownReadPlatformService
                .retrieveClientCalculationTypes();
        final List<EnumOptionData> clientChargeTimeTypeOptions = this.chargeDropdownReadPlatformService.retrieveClientCollectionTimeTypes();
        final List<EnumOptionData> feeFrequencyOptions = this.dropdownReadPlatformService.retrievePeriodFrequencyTypeOptions();
        final Map<String, List<GLAccountData>> incomeOrLiabilityAccountOptions = this.accountingDropdownReadPlatformService
                .retrieveAccountMappingOptionsForCharges();
        final List<EnumOptionData> shareChargeCalculationTypeOptions = this.chargeDropdownReadPlatformService
                .retrieveSharesCalculationTypes();
        final List<EnumOptionData> shareChargeTimeTypeOptions = this.chargeDropdownReadPlatformService.retrieveSharesCollectionTimeTypes();
        final Collection<TaxGroupData> taxGroupOptions = this.taxReadPlatformService.retrieveTaxGroupsForLookUp();
        final String accountMappingForChargeConfig = this.configurationDomainServiceJpa.getAccountMappingForCharge();
        final List<GLAccountData> expenseAccountOptions = this.accountingDropdownReadPlatformService.retrieveExpenseAccountOptions();
        final List<GLAccountData> assetAccountOptions = this.accountingDropdownReadPlatformService.retrieveAssetAccountOptions();

        return ChargeData.builder().currencyOptions(currencyOptions).chargeCalculationTypeOptions(allowedChargeCalculationTypeOptions)
                .chargeAppliesToOptions(allowedChargeAppliesToOptions).chargeTimeTypeOptions(allowedChargeTimeOptions)
                .chargePaymetModeOptions(chargePaymentOptions).loanChargeCalculationTypeOptions(loansChargeCalculationTypeOptions)
                .loanChargeTimeTypeOptions(loansChargeTimeTypeOptions)
                .savingsChargeCalculationTypeOptions(savingsChargeCalculationTypeOptions)
                .savingsChargeTimeTypeOptions(savingsChargeTimeTypeOptions)
                .clientChargeCalculationTypeOptions(clientChargeCalculationTypeOptions)
                .clientChargeTimeTypeOptions(clientChargeTimeTypeOptions).feeFrequencyOptions(feeFrequencyOptions)
                .incomeOrLiabilityAccountOptions(incomeOrLiabilityAccountOptions).taxGroupOptions(taxGroupOptions)
                .shareChargeCalculationTypeOptions(shareChargeCalculationTypeOptions).shareChargeTimeTypeOptions(shareChargeTimeTypeOptions)
                .accountMappingForChargeConfig(accountMappingForChargeConfig).expenseAccountOptions(expenseAccountOptions)
                .assetAccountOptions(assetAccountOptions).build();
    }

    @Override
    public List<ChargeData> retrieveLoanProductCharges(final Long loanProductId) {
        return this.chargeSqlRepository.loanProductCharges(loanProductId);
    }

    @Override
    public List<ChargeData> retrieveLoanProductCharges(final Long loanProductId, final ChargeTimeType chargeTime) {
        return this.chargeSqlRepository.loanProductCharges(loanProductId, chargeTime);
    }

    @Override
    public List<ChargeData> retrieveLoanApplicableFees() {
        return this.chargeSqlRepository.loanApplicableFees();
    }

    @Override
    public List<ChargeData> retrieveLoanAccountApplicableCharges(final Long loanId, ChargeTimeType[] excludeChargeTimes) {
        return this.chargeSqlRepository.loanAccountApplicableCharges(loanId, excludeChargeTimes);
    }

    @Override
    public List<ChargeData> retrieveLoanProductApplicableCharges(final Long loanProductId, ChargeTimeType[] excludeChargeTimes) {
        return this.chargeSqlRepository.loanProductApplicableCharges(loanProductId, excludeChargeTimes);
    }

    @Override
    public List<ChargeData> retrieveLoanApplicablePenalties() {
        return this.chargeSqlRepository.loanApplicablePenalties();
    }

    @Override
    public List<ChargeData> retrieveSavingsProductApplicableCharges(final boolean feeChargesOnly) {
        return this.chargeSqlRepository.savingsProductApplicableCharges(feeChargesOnly);
    }

    @Override
    public List<ChargeData> retrieveSavingsApplicablePenalties() {
        return this.chargeSqlRepository.savingsApplicablePenalties();
    }

    @Override
    public List<ChargeData> retrieveSavingsProductCharges(final Long savingsProductId) {
        return this.chargeSqlRepository.savingsProductCharges(savingsProductId);
    }

    @Override
    public List<ChargeData> retrieveShareProductCharges(final Long shareProductId) {
        return this.chargeSqlRepository.shareProductCharges(shareProductId);
    }

    @Override
    public List<ChargeData> retrieveWorkingCapitalLoanAccountApplicableCharges(Long loanId) {
        return this.chargeSqlRepository.workingCapitalLoanAccountApplicableCharges(loanId);
    }

    @Override
    public List<ChargeData> retrieveSavingsAccountApplicableCharges(Long savingsAccountId) {
        return this.chargeSqlRepository.savingsAccountApplicableCharges(savingsAccountId);
    }

    @Override
    public List<ChargeData> retrieveAllChargesApplicableToClients() {
        return this.chargeSqlRepository.allChargesApplicableToClients();
    }

    @Override
    public List<ChargeData> retrieveSharesApplicableCharges() {
        return this.chargeSqlRepository.sharesApplicableCharges();
    }
}
