/**
 * The MIT License
 * Copyright (c) 2014 Agile Knowledge Engineering and Semantic Web (AKSW) (usbeck@informatik.uni-leipzig.de)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.aksw.gerbil.execute;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.aksw.gerbil.annotator.Annotator;
import org.aksw.gerbil.annotator.C2KBAnnotator;
import org.aksw.gerbil.annotator.EntityExtractor;
import org.aksw.gerbil.annotator.EntityLinker;
import org.aksw.gerbil.annotator.EntityRecognizer;
import org.aksw.gerbil.annotator.EntityTyper;
import org.aksw.gerbil.annotator.OKETask1Annotator;
import org.aksw.gerbil.annotator.OKETask2Annotator;
import org.aksw.gerbil.annotator.decorator.ErrorCountingAnnotatorDecorator;
import org.aksw.gerbil.annotator.decorator.TimeMeasuringAnnotatorDecorator;
import org.aksw.gerbil.database.ExperimentDAO;
import org.aksw.gerbil.database.ResultNameToIdMapping;
import org.aksw.gerbil.dataset.Dataset;
import org.aksw.gerbil.datatypes.ErrorTypes;
import org.aksw.gerbil.datatypes.ExperimentTaskConfiguration;
import org.aksw.gerbil.datatypes.ExperimentTaskResult;
import org.aksw.gerbil.datatypes.ExperimentTaskState;
import org.aksw.gerbil.evaluate.DoubleEvaluationResult;
import org.aksw.gerbil.evaluate.EvaluationResult;
import org.aksw.gerbil.evaluate.EvaluationResultContainer;
import org.aksw.gerbil.evaluate.Evaluator;
import org.aksw.gerbil.evaluate.EvaluatorFactory;
import org.aksw.gerbil.evaluate.IntEvaluationResult;
import org.aksw.gerbil.evaluate.SubTaskResult;
import org.aksw.gerbil.evaluate.impl.FMeasureCalculator;
import org.aksw.gerbil.exceptions.GerbilException;
import org.aksw.gerbil.transfer.nif.Document;
import org.aksw.gerbil.transfer.nif.Marking;
import org.aksw.gerbil.transfer.nif.Meaning;
import org.aksw.gerbil.transfer.nif.MeaningSpan;
import org.aksw.gerbil.transfer.nif.Span;
import org.aksw.gerbil.transfer.nif.TypedSpan;
import org.aksw.gerbil.transfer.nif.data.TypedNamedEntity;
import org.aksw.simba.topicmodeling.concurrent.tasks.Task;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a single experiment designed as {@link Task} to be able to run
 * several tasks in parallel.
 * 
 * @author Michael R&ouml;der (roeder@informatik.uni-leipzig.de)
 *
 */
public class ExperimentTask implements Task {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExperimentTask.class);

    private ExperimentDAO experimentDAO;
    private ExperimentTaskConfiguration configuration;
    private int experimentTaskId;
    private EvaluatorFactory evFactory;
    private ExperimentTaskState taskState = null;
    private AnnotatorOutputWriter annotatorOutputWriter = null;

    public ExperimentTask(int experimentTaskId, ExperimentDAO experimentDAO,
            org.aksw.gerbil.evaluate.EvaluatorFactory evFactory, ExperimentTaskConfiguration configuration) {
        this.experimentDAO = experimentDAO;
        this.configuration = configuration;
        this.experimentTaskId = experimentTaskId;
        this.evFactory = evFactory;
    }

    @Override
    public void run() {
        LOGGER.info("Task started " + configuration.toString());
        Annotator annotator = null;
        try {
            // Create dataset
            Dataset dataset = (Dataset) configuration.datasetConfig.getDataset(configuration.type);
            if (dataset == null) {
                throw new GerbilException("dataset=\"" + configuration.datasetConfig.getName() + "\" experimentType=\""
                        + configuration.type.name() + "\".", ErrorTypes.DATASET_DOES_NOT_SUPPORT_EXPERIMENT);
            }

            // Create annotator
            annotator = (Annotator) configuration.annotatorConfig.getAnnotator(configuration.type);
            Annotator decoratedAnnotator = annotator;
            // Add decroatoring evaluators
            TimeMeasuringAnnotatorDecorator timeMeasurer = TimeMeasuringAnnotatorDecorator
                    .createDecorator(configuration.type, decoratedAnnotator);
            decoratedAnnotator = timeMeasurer;
            ErrorCountingAnnotatorDecorator errorCounter = ErrorCountingAnnotatorDecorator
                    .createDecorator(configuration.type, decoratedAnnotator, dataset.size());
            decoratedAnnotator = errorCounter;
            if (annotator == null) {
                throw new GerbilException("annotator=\"" + configuration.annotatorConfig.getName()
                        + "\" experimentType=\"" + configuration.type.name() + "\".",
                        ErrorTypes.ANNOTATOR_DOES_NOT_SUPPORT_EXPERIMENT);
            }

            List<Evaluator<?>> evaluators = new ArrayList<Evaluator<?>>();
            evFactory.addEvaluators(evaluators, configuration, dataset);
            evaluators.add(timeMeasurer);
            evaluators.add(errorCounter);

            taskState = new ExperimentTaskState(dataset.size());
            // perform experiment
            EvaluationResult result = runExperiment(dataset, decoratedAnnotator, evaluators, taskState);

            // create result object
            // FIXME Fix this workaround
            ExperimentTaskResult expResult = new ExperimentTaskResult(configuration, new double[6],
                    ExperimentDAO.TASK_FINISHED, 0);
            transformResults(result, expResult);

            // store result
            experimentDAO.setExperimentTaskResult(experimentTaskId, expResult);
            LOGGER.info("Task Finished " + configuration.toString());
        } catch (GerbilException e) {
            LOGGER.error("Got an error while running the task. Storing the error code in the db...", e);
            // store error
            experimentDAO.setExperimentState(experimentTaskId, e.getErrorType().getErrorCode());
        } catch (Exception e) {
            LOGGER.error("Error while trying to execute experiment.", e);
        } finally {
            IOUtils.closeQuietly(annotator);
        }
    }

    private void transformResults(EvaluationResult result, ExperimentTaskResult expResult) {
        if (result instanceof SubTaskResult) {
            ExperimentTaskResult subTask = new ExperimentTaskResult(((SubTaskResult) result).getConfiguration(),
                    new double[6], ExperimentDAO.TASK_FINISHED, 0);
            List<EvaluationResult> tempResults = ((EvaluationResultContainer) result).getResults();
            for (EvaluationResult tempResult : tempResults) {
                transformResults(tempResult, subTask);
            }
            expResult.addSubTask(subTask);
        }
        if (result instanceof EvaluationResultContainer) {
            List<EvaluationResult> tempResults = ((EvaluationResultContainer) result).getResults();
            for (EvaluationResult tempResult : tempResults) {
                transformResults(tempResult, expResult);
            }
        } else if (result instanceof DoubleEvaluationResult) {
            switch (result.getName()) {
            case FMeasureCalculator.MACRO_F1_SCORE_NAME: {
                expResult.results[ExperimentTaskResult.MACRO_F1_MEASURE_INDEX] = ((DoubleEvaluationResult) result)
                        .getValueAsDouble();
                return;
            }
            case FMeasureCalculator.MACRO_PRECISION_NAME: {
                expResult.results[ExperimentTaskResult.MACRO_PRECISION_INDEX] = ((DoubleEvaluationResult) result)
                        .getValueAsDouble();
                return;
            }
            case FMeasureCalculator.MACRO_RECALL_NAME: {
                expResult.results[ExperimentTaskResult.MACRO_RECALL_INDEX] = ((DoubleEvaluationResult) result)
                        .getValueAsDouble();
                return;
            }
            case FMeasureCalculator.MICRO_F1_SCORE_NAME: {
                expResult.results[ExperimentTaskResult.MICRO_F1_MEASURE_INDEX] = ((DoubleEvaluationResult) result)
                        .getValueAsDouble();
                return;
            }
            case FMeasureCalculator.MICRO_PRECISION_NAME: {
                expResult.results[ExperimentTaskResult.MICRO_PRECISION_INDEX] = ((DoubleEvaluationResult) result)
                        .getValueAsDouble();
                return;
            }
            case FMeasureCalculator.MICRO_RECALL_NAME: {
                expResult.results[ExperimentTaskResult.MICRO_RECALL_INDEX] = ((DoubleEvaluationResult) result)
                        .getValueAsDouble();
                return;
            }
            default: {
                int id = ResultNameToIdMapping.getInstance().getResultId(result.getName());
                if (id == ResultNameToIdMapping.UKNOWN_RESULT_TYPE) {
                    LOGGER.error("Got an unknown additional result \"" + result.getName() + "\". Discarding it.");
                } else {
                    expResult.addAdditionalResult(id, ((DoubleEvaluationResult) result).getValueAsDouble());
                }
            }
            }
            return;
        } else if (result instanceof IntEvaluationResult) {
            if (result.getName().equals(ErrorCountingAnnotatorDecorator.ERROR_COUNT_RESULT_NAME)) {
                expResult.errorCount = ((IntEvaluationResult) result).getValueAsInt();
                return;
            }
            int id = ResultNameToIdMapping.getInstance().getResultId(result.getName());
            if (id == ResultNameToIdMapping.UKNOWN_RESULT_TYPE) {
                LOGGER.error("Got an unknown additional result \"" + result.getName() + "\". Discarding it.");
            } else {
                expResult.addAdditionalResult(id, ((IntEvaluationResult) result).getValueAsInt());
            }
        }
    }

    @SuppressWarnings({ "deprecation" })
    private EvaluationResult runExperiment(Dataset dataset, Annotator annotator,
            List<Evaluator<? extends Marking>> evaluators, ExperimentTaskState state) throws GerbilException {
        EvaluationResult evalResult = null;
        switch (configuration.type) {
        case D2KB:
        case ELink: {
            try {
                List<List<MeaningSpan>> results = new ArrayList<List<MeaningSpan>>(dataset.size());
                List<List<MeaningSpan>> goldStandard = new ArrayList<List<MeaningSpan>>(dataset.size());
                EntityLinker linker = ((EntityLinker) annotator);

                for (Document document : dataset.getInstances()) {
                    // reduce the document to a text and a list of Spans
                    results.add(linker.performLinking(DocumentInformationReducer.reduceToTextAndSpans(document)));
                    goldStandard.add(document.getMarkings(MeaningSpan.class));
                    taskState.increaseExperimentStepCount();
                }
                if (annotatorOutputWriter != null) {
                    annotatorOutputWriter.storeAnnotatorOutput(configuration, results, dataset.getInstances());
                }
                evalResult = evaluate(evaluators, results, goldStandard);
            } catch (Exception e) {
                throw new GerbilException(e, ErrorTypes.UNEXPECTED_EXCEPTION);
            }
            break;
        }
        case A2KB:
        case Sa2KB:
        case EExt: {
            try {
                List<List<MeaningSpan>> results = new ArrayList<List<MeaningSpan>>(dataset.size());
                List<List<MeaningSpan>> goldStandard = new ArrayList<List<MeaningSpan>>(dataset.size());
                EntityExtractor extractor = ((EntityExtractor) annotator);
                for (Document document : dataset.getInstances()) {
                    // reduce the document to a single text
                    results.add(extractor.performExtraction(DocumentInformationReducer.reduceToPlainText(document)));
                    goldStandard.add(document.getMarkings(MeaningSpan.class));
                    taskState.increaseExperimentStepCount();
                }
                if (annotatorOutputWriter != null) {
                    annotatorOutputWriter.storeAnnotatorOutput(configuration, results, dataset.getInstances());
                }
                evalResult = evaluate(evaluators, results, goldStandard);
            } catch (Exception e) {
                throw new GerbilException(e, ErrorTypes.UNEXPECTED_EXCEPTION);
            }
            break;
        }
        case C2KB: {
            try {
                List<List<Meaning>> results = new ArrayList<List<Meaning>>(dataset.size());
                List<List<Meaning>> goldStandard = new ArrayList<List<Meaning>>(dataset.size());
                C2KBAnnotator c2KBAnnotator = ((C2KBAnnotator) annotator);

                for (Document document : dataset.getInstances()) {
                    // reduce the document to a text and a list of Spans
                    results.add(c2KBAnnotator.performC2KB(DocumentInformationReducer.reduceToPlainText(document)));
                    goldStandard.add(document.getMarkings(Meaning.class));
                    taskState.increaseExperimentStepCount();
                }
                if (annotatorOutputWriter != null) {
                    annotatorOutputWriter.storeAnnotatorOutput(configuration, results, dataset.getInstances());
                }
                evalResult = evaluate(evaluators, results, goldStandard);
            } catch (Exception e) {
                throw new GerbilException(e, ErrorTypes.UNEXPECTED_EXCEPTION);
            }
            break;
        }
        case Sc2KB: // Falls through
        case Rc2KB: {
            throw new GerbilException(ErrorTypes.UNEXPECTED_EXCEPTION);
        }
        case ERec: {
            try {
                List<List<Span>> results = new ArrayList<List<Span>>(dataset.size());
                List<List<Span>> goldStandard = new ArrayList<List<Span>>(dataset.size());
                EntityRecognizer recognizer = ((EntityRecognizer) annotator);
                for (Document document : dataset.getInstances()) {
                    // reduce the document to a single text
                    results.add(recognizer.performRecognition(DocumentInformationReducer.reduceToPlainText(document)));
                    goldStandard.add(document.getMarkings(Span.class));
                    taskState.increaseExperimentStepCount();
                }
                if (annotatorOutputWriter != null) {
                    annotatorOutputWriter.storeAnnotatorOutput(configuration, results, dataset.getInstances());
                }
                evalResult = evaluate(evaluators, results, goldStandard);
            } catch (Exception e) {
                throw new GerbilException(e, ErrorTypes.UNEXPECTED_EXCEPTION);
            }
            break;
        }
        case ETyping: {
            try {
                List<List<TypedSpan>> results = new ArrayList<List<TypedSpan>>(dataset.size());
                List<List<TypedSpan>> goldStandard = new ArrayList<List<TypedSpan>>(dataset.size());
                EntityTyper typer = ((EntityTyper) annotator);

                for (Document document : dataset.getInstances()) {
                    // reduce the document to a text and a list of Spans
                    results.add(typer.performTyping(DocumentInformationReducer.reduceToTextAndSpans(document)));
                    goldStandard.add(document.getMarkings(TypedSpan.class));
                    taskState.increaseExperimentStepCount();
                }
                if (annotatorOutputWriter != null) {
                    annotatorOutputWriter.storeAnnotatorOutput(configuration, results, dataset.getInstances());
                }
                evalResult = evaluate(evaluators, results, goldStandard);
            } catch (Exception e) {
                throw new GerbilException(e, ErrorTypes.UNEXPECTED_EXCEPTION);
            }
            break;
        }
        case OKE_Task1: {
            try {
                List<List<TypedNamedEntity>> results = new ArrayList<List<TypedNamedEntity>>(dataset.size());
                List<List<TypedNamedEntity>> goldStandard = new ArrayList<List<TypedNamedEntity>>(dataset.size());
                OKETask1Annotator okeTask1Annotator = ((OKETask1Annotator) annotator);

                for (Document document : dataset.getInstances()) {
                    // reduce the document to a text and a list of Spans
                    results.add(
                            okeTask1Annotator.performTask1(DocumentInformationReducer.reduceToTextAndSpans(document)));
                    goldStandard.add(document.getMarkings(TypedNamedEntity.class));
                    taskState.increaseExperimentStepCount();
                }
                if (annotatorOutputWriter != null) {
                    annotatorOutputWriter.storeAnnotatorOutput(configuration, results, dataset.getInstances());
                }
                evalResult = evaluate(evaluators, results, goldStandard);
            } catch (Exception e) {
                throw new GerbilException(e, ErrorTypes.UNEXPECTED_EXCEPTION);
            }
            break;
        }
        case OKE_Task2: {
            try {
                List<List<TypedNamedEntity>> results = new ArrayList<List<TypedNamedEntity>>(dataset.size());
                List<List<TypedNamedEntity>> goldStandard = new ArrayList<List<TypedNamedEntity>>(dataset.size());
                OKETask2Annotator okeTask2Annotator = ((OKETask2Annotator) annotator);

                for (Document document : dataset.getInstances()) {
                    // reduce the document to a text and a list of Spans
                    results.add(okeTask2Annotator
                            .performTask2(DocumentInformationReducer.reduceToTextAndEntities(document)));
                    goldStandard.add(document.getMarkings(TypedNamedEntity.class));
                    taskState.increaseExperimentStepCount();
                }
                if (annotatorOutputWriter != null) {
                    annotatorOutputWriter.storeAnnotatorOutput(configuration, results, dataset.getInstances());
                }
                evalResult = evaluate(evaluators, results, goldStandard);
            } catch (Exception e) {
                throw new GerbilException(e, ErrorTypes.UNEXPECTED_EXCEPTION);
            }
            break;
        }
        default:
            throw new GerbilException("This experiment type isn't implemented yet. Sorry for this.",
                    ErrorTypes.UNEXPECTED_EXCEPTION);
        }
        return evalResult;
    }

    @SuppressWarnings("unchecked")
    protected <T extends Marking> EvaluationResult evaluate(List<Evaluator<? extends Marking>> evaluators,
            List<List<T>> annotatorResults, List<List<T>> goldStandard) {
        EvaluationResultContainer evalResults = new EvaluationResultContainer();
        for (Evaluator<? extends Marking> e : evaluators) {
            ((Evaluator<T>) e).evaluate(annotatorResults, goldStandard, evalResults);
        }
        return evalResults;
    }

    @Override
    public String getId() {
        return configuration.toString();
    }

    @Override
    public String getProgress() {
        if (taskState != null) {
            return (taskState.getExperimentTaskProcess() * 100.0) + "% of dataset";
        } else {
            return null;
        }
    }

    public void setAnnotatorOutputWriter(AnnotatorOutputWriter annotatorOutputWriter) {
        this.annotatorOutputWriter = annotatorOutputWriter;
    }
}