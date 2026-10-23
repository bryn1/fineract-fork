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
package org.apache.fineract.accounting.closure.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.closure.data.GLClosureData;
import org.apache.fineract.accounting.closure.domain.GLClosureReadRepository;
import org.apache.fineract.accounting.closure.exception.GLClosureNotFoundException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GLClosureReadPlatformServiceImpl implements GLClosureReadPlatformService {

    // S4c peel (MC 11183): SQL execution moved byte-identical into GLClosureReadRepository behind
    // AccountingNativeSqlExecutor (S4-T converge leg); exception semantics unchanged (the repository
    // raises the same EmptyResultDataAccessException the executor mirrors from JdbcTemplate).
    private final GLClosureReadRepository glClosureReadRepository;

    @Override
    public List<GLClosureData> retrieveAllGLClosures(final Long officeId) {
        return this.glClosureReadRepository.retrieveAllGLClosures(officeId);
    }

    @Override
    public GLClosureData retrieveGLClosureById(final long glClosureId) {
        try {
            return this.glClosureReadRepository.retrieveGLClosureById(glClosureId);
        } catch (final EmptyResultDataAccessException e) {
            throw new GLClosureNotFoundException(glClosureId, e);
        }
    }

}
