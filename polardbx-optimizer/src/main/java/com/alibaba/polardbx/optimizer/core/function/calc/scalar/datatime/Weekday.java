/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.polardbx.optimizer.core.function.calc.scalar.datatime;

import com.alibaba.polardbx.optimizer.core.datatype.DataType;

import java.util.List;

/**
 * Returns the weekday index for date (0 = Monday, 1 = Tuesday, … 6 = Sunday).
 */
public class Weekday extends DayOfWeek {
    public Weekday(List<DataType> operandTypes, DataType resultType) {
        super(operandTypes, resultType);
    }

    @Override
    public String[] getFunctionNames() {
        return new String[] {"WEEKDAY"};
    }

    /**
     * according to Item* Create_func_weekday::create(THD *thd, Item *arg1)
     */
    @Override
    protected boolean odbcType() {
        return false;
    }
}