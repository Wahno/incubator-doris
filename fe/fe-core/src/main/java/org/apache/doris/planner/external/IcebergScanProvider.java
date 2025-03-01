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

package org.apache.doris.planner.external;

import org.apache.doris.analysis.Analyzer;
import org.apache.doris.analysis.BaseTableRef;
import org.apache.doris.analysis.BinaryPredicate;
import org.apache.doris.analysis.Expr;
import org.apache.doris.analysis.SlotRef;
import org.apache.doris.analysis.StringLiteral;
import org.apache.doris.analysis.TableName;
import org.apache.doris.analysis.TableRef;
import org.apache.doris.analysis.TupleDescriptor;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.PrimitiveType;
import org.apache.doris.catalog.TableIf;
import org.apache.doris.catalog.external.ExternalTable;
import org.apache.doris.catalog.external.HMSExternalTable;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.MetaNotFoundException;
import org.apache.doris.common.UserException;
import org.apache.doris.external.iceberg.util.IcebergUtils;
import org.apache.doris.planner.ColumnRange;
import org.apache.doris.thrift.TFileFormatType;
import org.apache.doris.thrift.TFileRangeDesc;
import org.apache.doris.thrift.TIcebergDeleteFileDesc;
import org.apache.doris.thrift.TIcebergFileDesc;
import org.apache.doris.thrift.TIcebergTable;
import org.apache.doris.thrift.TTableDescriptor;
import org.apache.doris.thrift.TTableFormatFileDesc;
import org.apache.doris.thrift.TTableType;

import lombok.Data;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileContent;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.types.Conversions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A file scan provider for iceberg.
 */
public class IcebergScanProvider extends HiveScanProvider {

    private static final int MIN_DELETE_FILE_SUPPORT_VERSION = 2;
    public static final String V2_DELETE_TBL = "iceberg#delete#tbl";
    public static final String V2_DELETE_DB = "iceberg#delete#db";
    private static final DeleteFileTempTable scanDeleteTable =
            new DeleteFileTempTable(TableIf.TableType.HMS_EXTERNAL_TABLE);
    private final Analyzer analyzer;

    public IcebergScanProvider(HMSExternalTable hmsTable, Analyzer analyzer, TupleDescriptor desc,
                               Map<String, ColumnRange> columnNameToRange) {
        super(hmsTable, desc, columnNameToRange);
        this.analyzer = analyzer;
    }

    public static void setIcebergParams(TFileRangeDesc rangeDesc, IcebergSplit icebergSplit)
            throws UserException {
        TTableFormatFileDesc tableFormatFileDesc = new TTableFormatFileDesc();
        tableFormatFileDesc.setTableFormatType(icebergSplit.getTableFormatType().value());
        TIcebergFileDesc fileDesc = new TIcebergFileDesc();
        int formatVersion = icebergSplit.getFormatVersion();
        fileDesc.setFormatVersion(formatVersion);
        if (formatVersion < MIN_DELETE_FILE_SUPPORT_VERSION) {
            fileDesc.setContent(FileContent.DATA.id());
        } else {
            setPathSelectConjunct(fileDesc, icebergSplit);
            for (IcebergDeleteFileFilter filter : icebergSplit.getDeleteFileFilters()) {
                TIcebergDeleteFileDesc deleteFileDesc = new TIcebergDeleteFileDesc();
                deleteFileDesc.setPath(filter.getDeleteFilePath());
                if (filter instanceof IcebergDeleteFileFilter.PositionDelete) {
                    fileDesc.setContent(FileContent.POSITION_DELETES.id());
                    IcebergDeleteFileFilter.PositionDelete positionDelete =
                            (IcebergDeleteFileFilter.PositionDelete) filter;
                    OptionalLong lowerBound = positionDelete.getPositionLowerBound();
                    OptionalLong upperBound = positionDelete.getPositionUpperBound();
                    if (lowerBound.isPresent()) {
                        deleteFileDesc.setPositionLowerBound(lowerBound.getAsLong());
                    }
                    if (upperBound.isPresent()) {
                        deleteFileDesc.setPositionUpperBound(upperBound.getAsLong());
                    }
                } else {
                    fileDesc.setContent(FileContent.EQUALITY_DELETES.id());
                    IcebergDeleteFileFilter.EqualityDelete equalityDelete =
                            (IcebergDeleteFileFilter.EqualityDelete) filter;
                    deleteFileDesc.setFieldIds(equalityDelete.getFieldIds());
                }
                fileDesc.addToDeleteFiles(deleteFileDesc);
            }
        }
        tableFormatFileDesc.setIcebergParams(fileDesc);
        rangeDesc.setTableFormatParams(tableFormatFileDesc);
    }

    private static void setPathSelectConjunct(TIcebergFileDesc fileDesc, IcebergSplit icebergSplit)
                throws UserException {
        BaseTableRef tableRef = icebergSplit.getDeleteTableRef();
        fileDesc.setDeleteTableTupleId(tableRef.getDesc().getId().asInt());
        SlotRef lhs = new SlotRef(tableRef.getName(), DeleteFileTempTable.DATA_FILE_PATH);
        lhs.analyze(icebergSplit.getAnalyzer());
        lhs.getDesc().setIsMaterialized(true);
        StringLiteral rhs = new StringLiteral(icebergSplit.getPath().toUri().toString());
        BinaryPredicate pathSelectConjunct = new BinaryPredicate(BinaryPredicate.Operator.EQ, lhs, rhs);
        pathSelectConjunct.analyze(icebergSplit.getAnalyzer());
        fileDesc.setFileSelectConjunct(pathSelectConjunct.treeToThrift());
    }

    @Override
    public TFileFormatType getFileFormatType() throws DdlException, MetaNotFoundException {
        TFileFormatType type;

        String icebergFormat = getRemoteHiveTable().getParameters()
                .getOrDefault(TableProperties.DEFAULT_FILE_FORMAT, TableProperties.DEFAULT_FILE_FORMAT_DEFAULT);
        if (icebergFormat.equalsIgnoreCase("parquet")) {
            type = TFileFormatType.FORMAT_PARQUET;
        } else if (icebergFormat.equalsIgnoreCase("orc")) {
            type = TFileFormatType.FORMAT_ORC;
        } else {
            throw new DdlException(String.format("Unsupported format name: %s for iceberg table.", icebergFormat));
        }
        return type;
    }

    @Override
    public List<InputSplit> getSplits(List<Expr> exprs) throws UserException {
        List<Expression> expressions = new ArrayList<>();
        for (Expr conjunct : exprs) {
            Expression expression = IcebergUtils.convertToIcebergExpr(conjunct);
            if (expression != null) {
                expressions.add(expression);
            }
        }

        org.apache.iceberg.Table table = getIcebergTable();
        TableScan scan = table.newScan();
        for (Expression predicate : expressions) {
            scan = scan.filter(predicate);
        }
        List<InputSplit> splits = new ArrayList<>();
        int formatVersion = ((BaseTable) table).operations().current().formatVersion();
        BaseTableRef tableRef = null;
        if (formatVersion >= MIN_DELETE_FILE_SUPPORT_VERSION) {
            TableName fullName = analyzer.getFqTableName(scanDeleteTable.getTableName());
            fullName.analyze(analyzer);
            TableRef ref = new TableRef(fullName, fullName.toString(), null);
            tableRef = new BaseTableRef(ref, scanDeleteTable, scanDeleteTable.getTableName());
            tableRef.analyze(analyzer);
        }
        for (FileScanTask task : scan.planFiles()) {
            for (FileScanTask spitTask : task.split(128 * 1024 * 1024)) {
                String dataFilePath = spitTask.file().path().toString();
                IcebergSplit split = new IcebergSplit(new Path(dataFilePath), spitTask.start(),
                        spitTask.length(), new String[0]);
                split.setFormatVersion(formatVersion);
                if (formatVersion >= MIN_DELETE_FILE_SUPPORT_VERSION) {
                    split.setDeleteFileFilters(getDeleteFileFilters(spitTask));
                    split.setDeleteTableRef(tableRef);
                }
                split.setTableFormatType(TableFormatType.ICEBERG);
                split.setAnalyzer(analyzer);
                splits.add(split);
            }
        }
        return splits;
    }

    private List<IcebergDeleteFileFilter> getDeleteFileFilters(FileScanTask spitTask) {
        List<IcebergDeleteFileFilter> filters = new ArrayList<>();
        for (DeleteFile delete : spitTask.deletes()) {
            if (delete.content() == FileContent.POSITION_DELETES) {
                ByteBuffer lowerBoundBytes = delete.lowerBounds().get(MetadataColumns.DELETE_FILE_POS.fieldId());
                Optional<Long> positionLowerBound = Optional.ofNullable(lowerBoundBytes)
                        .map(bytes -> Conversions.fromByteBuffer(MetadataColumns.DELETE_FILE_POS.type(), bytes));
                ByteBuffer upperBoundBytes = delete.upperBounds().get(MetadataColumns.DELETE_FILE_POS.fieldId());
                Optional<Long> positionUpperBound = Optional.ofNullable(upperBoundBytes)
                        .map(bytes -> Conversions.fromByteBuffer(MetadataColumns.DELETE_FILE_POS.type(), bytes));
                filters.add(IcebergDeleteFileFilter.createPositionDelete(delete.path().toString(),
                        positionLowerBound.orElse(-1L), positionUpperBound.orElse(-1L)));
            } else if (delete.content() == FileContent.EQUALITY_DELETES) {
                // todo: filters.add(IcebergDeleteFileFilter.createEqualityDelete(delete.path().toString(),
                // delete.equalityFieldIds()));
                throw new IllegalStateException("Don't support equality delete file");
            } else {
                throw new IllegalStateException("Unknown delete content: " + delete.content());
            }
        }
        return filters;
    }


    private org.apache.iceberg.Table getIcebergTable() throws MetaNotFoundException {
        org.apache.iceberg.hive.HiveCatalog hiveCatalog = new org.apache.iceberg.hive.HiveCatalog();
        Configuration conf = setConfiguration();
        hiveCatalog.setConf(conf);
        // initialize hive catalog
        Map<String, String> catalogProperties = new HashMap<>();
        catalogProperties.put("hive.metastore.uris", getMetaStoreUrl());
        catalogProperties.put("uri", getMetaStoreUrl());
        hiveCatalog.initialize("hive", catalogProperties);

        return hiveCatalog.loadTable(TableIdentifier.of(hmsTable.getDbName(), hmsTable.getName()));
    }

    @Override
    public List<String> getPathPartitionKeys() throws DdlException, MetaNotFoundException {
        return Collections.emptyList();
    }

    @Data
    static class DeleteFileTempTable extends ExternalTable {
        public static final String DATA_FILE_PATH = "file_path";
        private final TableName tableName;
        private final List<Column> fullSchema = new ArrayList<>();

        public DeleteFileTempTable(TableType type) {
            super(0, V2_DELETE_TBL, null, V2_DELETE_DB, type);
            this.tableName = new TableName(null, V2_DELETE_DB, V2_DELETE_TBL);
            Column dataFilePathCol = new Column(DATA_FILE_PATH, PrimitiveType.STRING, true);
            this.fullSchema.add(dataFilePathCol);
        }

        @Override
        public List<Column> getFullSchema() {
            return fullSchema;
        }

        @Override
        public TTableDescriptor toThrift() {
            TIcebergTable tIcebergTable = new TIcebergTable(V2_DELETE_DB, V2_DELETE_TBL, new HashMap<>());
            TTableDescriptor tTableDescriptor = new TTableDescriptor(getId(), TTableType.ICEBERG_TABLE,
                    fullSchema.size(), 0, getName(), "");
            tTableDescriptor.setIcebergTable(tIcebergTable);
            return tTableDescriptor;
        }
    }
}
