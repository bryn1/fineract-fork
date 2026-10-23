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
package org.apache.fineract.accounting.rule.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.common.AccountingEnumerations;
import org.apache.fineract.accounting.common.AccountingNativeSqlExecutor;
import org.apache.fineract.accounting.glaccount.data.GLAccountDataForLookup;
import org.apache.fineract.accounting.glaccount.domain.GLAccountReadRepository;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryType;
import org.apache.fineract.accounting.rule.data.AccountingRuleData;
import org.apache.fineract.accounting.rule.data.AccountingTagRuleData;
import org.apache.fineract.infrastructure.codes.data.CodeValueData;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * S3 peel (MC 11163): the read-platform SQL of {@link org.apache.fineract.accounting.rule.service
 * .AccountingRuleReadPlatformServiceImpl}, moved verbatim — queries, the map-building ResultSetExtractor and
 * the tag-row mapper included — behind a module repository so the service holds no SQL execution. The
 * extractor's tag-account association reaches the accounts through {@link GLAccountReadRepository} (the same
 * query the read service delegated to). See {@link AccountingNativeSqlExecutor} for the execution contract.
 */
@Repository
@RequiredArgsConstructor
public class AccountingRuleReadRepository {

    private final AccountingNativeSqlExecutor nativeSqlExecutor;
    private final GLAccountReadRepository glAccountReadRepository;

    private static final class AccountingRuleDataExtractor implements ResultSetExtractor<Map<Long, AccountingRuleData>> {

        private final String schemaSql;
        private final AccountingNativeSqlExecutor nativeSqlExecutor;
        private final GLAccountReadRepository glAccountReadRepository;
        private final boolean isAssociationParametersExists;

        AccountingRuleDataExtractor(final AccountingNativeSqlExecutor nativeSqlExecutor,
                final GLAccountReadRepository glAccountReadRepository, final boolean isAssociationParametersExists) {
            this.nativeSqlExecutor = nativeSqlExecutor;
            this.glAccountReadRepository = glAccountReadRepository;
            this.isAssociationParametersExists = isAssociationParametersExists;
            final StringBuilder sqlBuilder = new StringBuilder(400);
            sqlBuilder.append(" rule.id as id,rule.name as name, rule.office_id as officeId,office.name as officeName,").append(
                    " rule.description as description, rule.system_defined as systemDefined, rule.allow_multiple_debits as allowMultipleDebitEntries, rule.allow_multiple_credits as allowMultipleCreditEntries, ")
                    .append("debitAccount.id AS debitAccountId, debitAccount.name as debitAccountName, debitAccount.gl_code as debitAccountGLCode, ")
                    .append("creditAccount.id AS creditAccountId, creditAccount.name as creditAccountName, creditAccount.gl_code as creditAccountGLCode")
                    .append(" from m_office AS office, acc_accounting_rule AS rule ")
                    .append(" LEFT JOIN acc_gl_account AS creditAccount ON rule.credit_account_id = creditAccount.id ")
                    .append(" LEFT JOIN acc_gl_account AS debitAccount ON rule.debit_account_id = debitAccount.id ")
                    .append("WHERE office.id=rule.office_id ");
            this.schemaSql = sqlBuilder.toString();
        }

        public String schema() {
            return this.schemaSql;
        }

        @Override
        public Map<Long, AccountingRuleData> extractData(final ResultSet rs) throws SQLException, DataAccessException {
            final Map<Long, AccountingRuleData> extractedData = new HashMap<>();

            while (rs.next()) {
                final Long id = rs.getLong("id");
                AccountingRuleData accountingRuleData = extractedData.get(id);
                if (accountingRuleData == null) {
                    final Long officeId = JdbcSupport.getLong(rs, "officeId");
                    final String officeName = rs.getString("officeName");
                    final String name = rs.getString("name");
                    final String description = rs.getString("description");
                    final Long accountToDebitId = JdbcSupport.getLong(rs, "debitAccountId");
                    final Long accountToCreditId = JdbcSupport.getLong(rs, "creditAccountId");
                    final boolean systemDefined = rs.getBoolean("systemDefined");
                    final boolean allowMultipleDebitEntries = rs.getBoolean("allowMultipleDebitEntries");
                    final boolean allowMultipleCreditEntries = rs.getBoolean("allowMultipleCreditEntries");
                    final String debitAccountName = rs.getString("debitAccountName");
                    final String creditAccountName = rs.getString("creditAccountName");
                    final String debitAccountGLCode = rs.getString("debitAccountGLCode");
                    final String creditAccountGLCode = rs.getString("creditAccountGLCode");

                    final List<AccountingTagRuleData> creditTags;
                    final List<AccountingTagRuleData> debitTags;
                    final List<GLAccountDataForLookup> creditAccounts;
                    final List<GLAccountDataForLookup> debitAccounts;

                    if (accountToCreditId == null) {
                        creditTags = !this.isAssociationParametersExists ? getCreditOrDebitTags(id, JournalEntryType.CREDIT.getValue())
                                : null;
                        creditAccounts = this.isAssociationParametersExists
                                ? this.glAccountReadRepository.retrieveAccountsByTagId(id, JournalEntryType.CREDIT.getValue())
                                : null;
                    } else {
                        creditTags = null;
                        final GLAccountDataForLookup creditAccount = new GLAccountDataForLookup().setId(accountToCreditId)
                                .setName(creditAccountName).setGlCode(creditAccountGLCode);
                        creditAccounts = new ArrayList<>(Arrays.asList(creditAccount));
                    }
                    if (accountToDebitId == null) {
                        debitTags = !this.isAssociationParametersExists ? getCreditOrDebitTags(id, JournalEntryType.DEBIT.getValue())
                                : null;
                        debitAccounts = this.isAssociationParametersExists
                                ? this.glAccountReadRepository.retrieveAccountsByTagId(id, JournalEntryType.DEBIT.getValue())
                                : null;
                    } else {
                        debitTags = null;
                        final GLAccountDataForLookup debitAccount = new GLAccountDataForLookup().setId(accountToDebitId)
                                .setName(debitAccountName).setGlCode(debitAccountGLCode);
                        debitAccounts = new ArrayList<>(Arrays.asList(debitAccount));
                    }
                    accountingRuleData = new AccountingRuleData().setId(id).setOfficeId(officeId).setOfficeName(officeName).setName(name)
                            .setDescription(description).setSystemDefined(systemDefined)
                            .setAllowMultipleDebitEntries(allowMultipleDebitEntries)
                            .setAllowMultipleCreditEntries(allowMultipleCreditEntries).setCreditTags(creditTags).setDebitTags(debitTags)
                            .setCreditAccounts(creditAccounts).setDebitAccounts(debitAccounts);
                }

                extractedData.put(id, accountingRuleData);
            }
            return extractedData;
        }

        private List<AccountingTagRuleData> getCreditOrDebitTags(final Long creditOrDebitAccount, final Integer transactionType) {
            final AccountingTagRuleDataMapper mapper = new AccountingTagRuleDataMapper();
            final String taggedAccountsSchema = "Select " + mapper.taggedAccountSchema() + " where rule.id = ? and tag.acc_type_enum=?";
            return this.nativeSqlExecutor.queryForList(taggedAccountsSchema, Arrays.asList(creditOrDebitAccount, transactionType),
                    mapper); // NOSONAR
        }

    }

    public List<AccountingRuleData> retrieveAllAccountingRules(final String hierarchySearchString,
            final boolean isAssociationParametersExists) {
        final AccountingRuleDataExtractor resultSetExtractor = new AccountingRuleDataExtractor(this.nativeSqlExecutor,
                this.glAccountReadRepository, isAssociationParametersExists);
        Object[] arguments = new Object[] {};
        String sql = "select " + resultSetExtractor.schema() + " and system_defined=false ";
        if (hierarchySearchString != null) {
            sql = sql + " and office.hierarchy like ?";
            arguments = new Object[] { hierarchySearchString };
        }
        sql = sql + " order by rule.id asc";
        final Map<Long, AccountingRuleData> extractedData = this.nativeSqlExecutor.extract(sql, Arrays.asList(arguments),
                resultSetExtractor); // NOSONAR
        return new ArrayList<>(extractedData.values());
    }

    public Map<Long, AccountingRuleData> retrieveAccountingRulesMapById(final Long accountingRuleId) {
        final AccountingRuleDataExtractor resultSetExtractor = new AccountingRuleDataExtractor(this.nativeSqlExecutor,
                this.glAccountReadRepository, false);
        final String sql = "select " + resultSetExtractor.schema() + " and rule.id = ?";

        return this.nativeSqlExecutor.extract(sql, List.of(accountingRuleId), resultSetExtractor); // NOSONAR
    }

    private static final class AccountingTagRuleDataMapper implements RowMapper<AccountingTagRuleData> {

        public String taggedAccountSchema() {
            return " tag.id as id,tag.tag_id as tagId, tag.acc_type_enum as transactionType, cv.code_value as tagName from m_code_value cv join acc_rule_tags tag on tag.tag_id=cv.id "
                    + "join acc_accounting_rule rule on tag.acc_rule_id=rule.id ";
        }

        @Override
        public AccountingTagRuleData mapRow(final ResultSet rs, @SuppressWarnings("unused") final int rowNum) throws SQLException {
            final Long id = rs.getLong("id");
            final Long tagId = rs.getLong("tagId");
            final Integer transactionType = JdbcSupport.getInteger(rs, "transactionType");
            final String tagName = rs.getString("tagName");
            final CodeValueData tag = CodeValueData.instance(tagId, tagName);
            final EnumOptionData transactionTypeEnum = AccountingEnumerations.journalEntryType(transactionType);
            return new AccountingTagRuleData().setId(id).setTag(tag).setTransactionType(transactionTypeEnum);
        }

    }
}
