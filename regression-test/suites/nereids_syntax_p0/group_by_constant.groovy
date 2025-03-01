// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

suite("group_by_constant") {
    sql """
        SET enable_vectorized_engine=true
    """

    sql """
        SET enable_nereids_planner=true
    """

    sql "SET enable_fallback_to_original_planner=false"

    qt_select_1 """ 
        select 'str', sum(lo_tax), lo_orderkey, max(lo_discount), 1 from lineorder, customer group by 3, 5, 'str', 1, lo_orderkey order by lo_orderkey;
    """

    qt_sql """SELECT lo_custkey, lo_partkey, SUM(lo_tax) FROM lineorder GROUP BY 1, 2"""

    qt_sql """SELECT lo_partkey, lo_custkey, SUM(lo_tax) FROM lineorder GROUP BY 1, 2"""

    qt_sql """SELECT lo_partkey, 1, SUM(lo_tax) FROM lineorder GROUP BY 1,  1 + 1"""

    qt_sql """SELECT lo_partkey, 1, SUM(lo_tax) FROM lineorder GROUP BY 'g',  1"""

}
