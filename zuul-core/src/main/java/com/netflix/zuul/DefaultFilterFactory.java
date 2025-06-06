/*
 * Copyright 2018 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */
package com.netflix.zuul;

import com.netflix.zuul.filters.ZuulFilter;
import java.lang.reflect.InvocationTargetException;

/**
 * Default factory for creating instances of ZuulFilter.
 */
public class DefaultFilterFactory implements FilterFactory {

    /**
     * Returns a new implementation of ZuulFilter as specified by the provided
     * Class. The Class is instantiated using its nullary constructor.
     *
     * @param clazz the Class to instantiate
     * @return A new instance of ZuulFilter
     */
    @Override
    public ZuulFilter<?, ?> newInstance(Class<?> clazz)
            throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException,
                    NoSuchMethodException {
        return (ZuulFilter<?, ?>) clazz.getDeclaredConstructor().newInstance();
    }
}
