/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.query;

import com.google.common.collect.Lists;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.PublicTermsFilter;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.lucene.search.AndFilter;
import org.elasticsearch.common.lucene.search.TermFilter;
import org.elasticsearch.common.lucene.search.XBooleanFilter;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.cache.filter.support.CacheKeyFilter;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.MapperService;

import java.io.IOException;
import java.util.List;

import static org.elasticsearch.index.query.support.QueryParsers.wrapSmartNameFilter;

/**
 *
 */
public class TermsFilterParser implements FilterParser {

    public static final String NAME = "terms";

    @Inject
    public TermsFilterParser() {
    }

    @Override
    public String[] names() {
        return new String[]{NAME, "in"};
    }

    @Override
    public Filter parse(QueryParseContext parseContext) throws IOException, QueryParsingException {
        XContentParser parser = parseContext.parser();

        MapperService.SmartNameFieldMappers smartNameFieldMappers = null;
        Boolean cache = null;
        String filterName = null;
        String currentFieldName = null;
        CacheKeyFilter.Key cacheKey = null;
        XContentParser.Token token;
        String execution = "plain";
        List<String> terms = Lists.newArrayList();
        String fieldName = null;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_ARRAY) {
                fieldName = currentFieldName;

                while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                    String value = parser.text();
                    if (value == null) {
                        throw new QueryParsingException(parseContext.index(), "No value specified for term filter");
                    }
                    terms.add(value);
                }
            } else if (token.isValue()) {
                if ("execution".equals(currentFieldName)) {
                    execution = parser.text();
                } else if ("_name".equals(currentFieldName)) {
                    filterName = parser.text();
                } else if ("_cache".equals(currentFieldName)) {
                    cache = parser.booleanValue();
                } else if ("_cache_key".equals(currentFieldName) || "_cacheKey".equals(currentFieldName)) {
                    cacheKey = new CacheKeyFilter.Key(parser.text());
                } else {
                    throw new QueryParsingException(parseContext.index(), "[terms] filter does not support [" + currentFieldName + "]");
                }
            }
        }

        if (fieldName == null) {
            throw new QueryParsingException(parseContext.index(), "bool filter requires a field name, followed by array of terms");
        }

        FieldMapper fieldMapper = null;
        smartNameFieldMappers = parseContext.smartFieldMappers(fieldName);
        if (smartNameFieldMappers != null) {
            if (smartNameFieldMappers.hasMapper()) {
                fieldMapper = smartNameFieldMappers.mapper();
                fieldName = fieldMapper.names().indexName();
            }
        }


        Filter filter;
        if ("plain".equals(execution)) {
            PublicTermsFilter termsFilter = new PublicTermsFilter();
            if (fieldMapper != null) {
                for (String term : terms) {
                    termsFilter.addTerm(fieldMapper.names().createIndexNameTerm(fieldMapper.indexedValue(term)));
                }
            } else {
                for (String term : terms) {
                    termsFilter.addTerm(new Term(fieldName, term));
                }
            }
            filter = termsFilter;
            // cache the whole filter by default, or if explicitly told to
            if (cache == null || cache) {
                filter = parseContext.cacheFilter(filter, cacheKey);
            }
        } else if ("bool".equals(execution)) {
            XBooleanFilter boolFiler = new XBooleanFilter();
            if (fieldMapper != null) {
                for (String term : terms) {
                    boolFiler.addShould(parseContext.cacheFilter(fieldMapper.fieldFilter(term, parseContext), null));
                }
            } else {
                for (String term : terms) {
                    boolFiler.addShould(parseContext.cacheFilter(new TermFilter(new Term(fieldName, term)), null));
                }
            }
            filter = boolFiler;
            // only cache if explicitly told to, since we cache inner filters
            if (cache != null && cache) {
                filter = parseContext.cacheFilter(filter, cacheKey);
            }
        } else if ("bool_nocache".equals(execution)) {
            XBooleanFilter boolFiler = new XBooleanFilter();
            if (fieldMapper != null) {
                for (String term : terms) {
                    boolFiler.addShould(fieldMapper.fieldFilter(term, parseContext));
                }
            } else {
                for (String term : terms) {
                    boolFiler.addShould(new TermFilter(new Term(fieldName, term)));
                }
            }
            filter = boolFiler;
            // cache the whole filter by default, or if explicitly told to
            if (cache == null || cache) {
                filter = parseContext.cacheFilter(filter, cacheKey);
            }
        } else if ("and".equals(execution)) {
            List<Filter> filters = Lists.newArrayList();
            if (fieldMapper != null) {
                for (String term : terms) {
                    filters.add(parseContext.cacheFilter(fieldMapper.fieldFilter(term, parseContext), null));
                }
            } else {
                for (String term : terms) {
                    filters.add(parseContext.cacheFilter(new TermFilter(new Term(fieldName, term)), null));
                }
            }
            filter = new AndFilter(filters);
            // only cache if explicitly told to, since we cache inner filters
            if (cache != null && cache) {
                filter = parseContext.cacheFilter(filter, cacheKey);
            }
        } else if ("and_nocache".equals(execution)) {
            List<Filter> filters = Lists.newArrayList();
            if (fieldMapper != null) {
                for (String term : terms) {
                    filters.add(fieldMapper.fieldFilter(term, parseContext));
                }
            } else {
                for (String term : terms) {
                    filters.add(new TermFilter(new Term(fieldName, term)));
                }
            }
            filter = new AndFilter(filters);
            // cache the whole filter by default, or if explicitly told to
            if (cache == null || cache) {
                filter = parseContext.cacheFilter(filter, cacheKey);
            }
        } else {
            throw new QueryParsingException(parseContext.index(), "bool filter execution value [" + execution + "] not supported");
        }

        filter = wrapSmartNameFilter(filter, smartNameFieldMappers, parseContext);
        if (filterName != null) {
            parseContext.addNamedFilter(filterName, filter);
        }
        return filter;
    }
}
