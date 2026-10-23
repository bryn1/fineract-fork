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
package org.apache.fineract.portfolio.charge.starter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import org.apache.fineract.accounting.common.AccountingDropdownReadPlatformService;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepositoryWrapper;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainServiceJpa;
import org.apache.fineract.infrastructure.entityaccess.service.FineractEntityAccessUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.service.CurrencyReadPlatformService;
import org.apache.fineract.portfolio.charge.domain.ChargeRepository;
import org.apache.fineract.portfolio.charge.serialization.ChargeDefinitionCommandFromApiJsonDeserializer;
import org.apache.fineract.portfolio.charge.service.ChargeDropdownReadPlatformService;
import org.apache.fineract.portfolio.charge.service.ChargeReadPlatformServiceImpl;
import org.apache.fineract.portfolio.charge.service.ChargeWritePlatformServiceJpaRepositoryImpl;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentTypeRepository;
import org.apache.fineract.portfolio.tax.service.TaxReadPlatformService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * S4a characterization suite (MC 11183): pins the CURRENT bean wiring of {@link ChargeConfiguration} at
 * pin 355287c8 — which concrete implementation each charge bean resolves to (the entry point an S4 peel
 * must keep intact). @ConditionalOnMissingBean semantics are Spring-proxy behavior and out of scope here;
 * the pin covers the factory methods' observable return types.
 */
class ChargeConfigurationCharacterizationTest {

    @Test
    void dropdownBeanIsThePureEnumServiceImpl() {
        assertNotNull(new ChargeConfiguration().chargeDropdownReadPlatformService());
        assertSame(org.apache.fineract.portfolio.charge.service.ChargeDropdownReadPlatformServiceImpl.class,
                new ChargeConfiguration().chargeDropdownReadPlatformService().getClass());
    }

    @Test
    void readAndWriteBeansResolveToTheirJdbcAndJpaImplementations() {
        ChargeConfiguration configuration = new ChargeConfiguration();

        Object read = configuration.chargeReadPlatformService(mock(CurrencyReadPlatformService.class),
                mock(ChargeDropdownReadPlatformService.class), mock(JdbcTemplate.class),
                mock(org.apache.fineract.portfolio.common.service.DropdownReadPlatformService.class), mock(FineractEntityAccessUtil.class),
                mock(AccountingDropdownReadPlatformService.class), mock(TaxReadPlatformService.class),
                mock(ConfigurationDomainServiceJpa.class), mock(NamedParameterJdbcTemplate.class));
        assertSame(ChargeReadPlatformServiceImpl.class, read.getClass());

        Object write = configuration.chargeWritePlatformService(
                mock(PlatformSecurityContext.class), mock(ChargeDefinitionCommandFromApiJsonDeserializer.class),
                mock(ChargeRepository.class), mock(LoanProductRepository.class), mock(JdbcTemplate.class),
                mock(FineractEntityAccessUtil.class), mock(GLAccountRepositoryWrapper.class),
                mock(org.apache.fineract.portfolio.tax.domain.TaxGroupRepositoryWrapper.class), mock(PaymentTypeRepository.class));
        assertSame(ChargeWritePlatformServiceJpaRepositoryImpl.class, write.getClass());
    }
}
