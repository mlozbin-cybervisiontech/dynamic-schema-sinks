/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.dynamicschema;

import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.api.common.Bytes;
import io.cdap.cdap.api.data.batch.Output;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.dataset.DatasetManagementException;
import io.cdap.cdap.api.dataset.DatasetProperties;
import io.cdap.cdap.api.dataset.lib.KeyValue;
import io.cdap.cdap.api.dataset.table.Put;
import io.cdap.cdap.api.dataset.table.Table;
import io.cdap.cdap.etl.api.Emitter;
import io.cdap.cdap.etl.api.PipelineConfigurer;
import io.cdap.cdap.etl.api.batch.BatchRuntimeContext;
import io.cdap.cdap.etl.api.batch.BatchSink;
import io.cdap.cdap.etl.api.batch.BatchSinkContext;
import io.cdap.dynamicschema.api.Expression;
import io.cdap.dynamicschema.api.ExpressionException;
import io.cdap.dynamicschema.api.ObserverException;
import io.cdap.dynamicschema.api.ValidationException;
import io.cdap.dynamicschema.observer.SchemaObserver;
import io.cdap.dynamicschema.observer.StructuredRecordObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Dynamic Schema support for writing to Table.
 */
@Plugin(type = BatchSink.PLUGIN_TYPE)
@Name("DynTable")
@Description("Dynamic Schema support for Table writes.")
public class DynamicSchemaTableSink extends BatchSink<StructuredRecord, byte[], Put> {
  private static final Logger LOG = LoggerFactory.getLogger(DynamicSchemaTableSink.class);

  /**
   * HBase Plugin configuration to read configuration from JSON.
   */
  private TableSinkConfig config;

  /**
   * Expression evaluator for generating row key.
   */
  private Expression rowKeyExpression;

  public DynamicSchemaTableSink(TableSinkConfig config) {
    this.config = config;
  }

  @Override
  public void configurePipeline(PipelineConfigurer configurer) {
    super.configurePipeline(configurer);
    // Get the input schema and validate if there are fields that
    // support dynamic schema.
    Schema schema = configurer.getStageConfigurer().getInputSchema();

    try {
      DynamicSchemaValidator dcv = new DynamicSchemaValidator();
      SchemaObserver so = new SchemaObserver(dcv);
      so.traverse(schema);
      dcv.validate();
    } catch (ValidationException e) {
      throw new IllegalArgumentException(e.getMessage());
    } catch (ObserverException e) {
      throw new IllegalArgumentException(e.getMessage());
    }

    // Compile Row Key Expression and make sure it's ok.
    try {
      rowKeyExpression = new Expression(config.getRowkey());
    } catch (ExpressionException e) {
      throw new IllegalArgumentException("Error in specifying row key " + e.getMessage());
    }

    // We kow check if all the variables in the expression are present in the input schema
    // and they are of simple types.
    List<String> variables = rowKeyExpression.getVariables();

    // If there are no variables, then row key is not correctly formed by the user.
    if (variables.size() == 0) {
      throw new IllegalArgumentException(
        String.format("Please specify a input field name or an expression. You cannot use a constant for row key.")
      );
    }

    for (String variable : variables) {
      Schema.Field field = schema.getField(variable);
      if (field == null) {
        throw new IllegalArgumentException(
          String.format("Row key expression '%s' has variable '%s' that is not present in input field",
                        config.rowkey, variable)
        );
      }
      if (!field.getSchema().isSimpleOrNullableSimple()) {
        throw new IllegalArgumentException(
          String.format("Row key expression '%s' has variable '%s' that is not of type " +
                          "'string', 'int', 'long', 'float', 'double'", config.rowkey, variable)
        );
      }
    }

    // If the table is not a macro, then create it.
    if(!config.containsMacro("table")) {
      configurer.createDataset(config.table, Table.class.getName(), DatasetProperties.builder().build());
    }
  }

  @Override
  public void prepareRun(BatchSinkContext context) throws DatasetManagementException {
    if (!context.datasetExists(config.table)) {
      context.createDataset(config.table, Table.class.getName(), DatasetProperties.builder().build());
    }
    context.addOutput(Output.ofDataset(config.table));
  }


  @Override
  public void initialize(BatchRuntimeContext context) throws Exception {
    super.initialize(context);
    // Row key resolver setup, we know by now that the expression is valid.
    rowKeyExpression = new Expression(config.rowkey);
  }

  @Override
  public void transform(StructuredRecord input, Emitter<KeyValue<byte[], Put>> emitter) throws Exception {
    String row = rowKeyExpression.apply(input);

    TablePutGenerator generator = new TablePutGenerator(Bytes.toBytes(row));
    StructuredRecordObserver sro = new StructuredRecordObserver(generator);
    sro.traverse(input);

    // Visit all the fields and perform necessary operations.
    emitter.emit(new KeyValue<byte[], Put>(Bytes.toBytes(row), generator.get()));
  }
}
