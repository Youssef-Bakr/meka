/*
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package meka.classifiers.multilabel;

import meka.classifiers.MultiXClassifier;
import meka.classifiers.multitarget.MultiTargetClassifier;
import meka.core.MLEvalUtils;
import meka.core.MLUtils;
import meka.core.Result;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.Option;
import weka.core.SerializationHelper;
import weka.core.Utils;
import weka.core.converters.AbstractFileSaver;
import weka.core.converters.ArffSaver;
import weka.core.converters.ConverterUtils;
import weka.core.converters.ConverterUtils.DataSink;
import weka.core.converters.ConverterUtils.DataSource;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Evaluation.java - Evaluation functionality.
 * @author 		Jesse Read
 * @version 	March 2014
 */
public class Evaluation {

	public static final char FLAG_HELP = 'h';

	public static final char FLAG_CLASSES = 'C';

	public static final char FLAG_SEED = 's';

	public static final char FLAG_RANDOMIZE = 'R';

	public static final String FLAG_THREADED = "Thr";

	public static final String FLAG_VERBOSITY = "verbosity";

	public static final char FLAG_DUMPMODEL = 'd';

	public static final char FLAG_LOADMODEL = 'l';

	public static final String FLAG_THRESHOLD = "threshold";

	public static final String FLAG_PREDICTIONS = "predictions";

	public static final String FLAG_NOEVAL = "no-eval";

	public static final char FLAG_CROSSVALIDATION = 'x';

	public static final String FLAG_CROSSVALIDATION_OUTDIR = "x-out-dir";

	public static final char FLAG_TRAINFILE = 't';

	public static final char FLAG_TESTFILE = 'T';

	public static final String FLAG_SPLITPERCENTAGE = "split-percentage";

	public static final String FLAG_SPLITNUMBER = "split-number";

	public static final char FLAG_INVERTSPLIT = 'i';

	/**
	 * RunExperiment - Build and evaluate a model with command-line options.
	 * @param	h		multi-label classifier
	 * @param	options	command line options
	 */
	public static void runExperiment(MultiLabelClassifier h, String options[]) throws Exception {

		// Help
		if(Utils.getOptionPos(FLAG_HELP,options) >= 0) {
			System.out.println("\nHelp requested");
			Evaluation.printOptions(h.listOptions());
			return;
		}

		// collect general commandline flags
		// cannot be done later on, as classifier will have a Utils.checkRemainingOptions call
		// resulting in an Exception of unhandled options
		String optTrainFile = Utils.getOption(FLAG_TRAINFILE, options);
		String optTestFile = Utils.getOption(FLAG_TESTFILE, options);
		String optClasses = Utils.getOption(FLAG_CLASSES, options);
		String optSeed = Utils.getOption(FLAG_SEED, options);
		boolean optRandomize = Utils.getFlag(FLAG_RANDOMIZE, options);
		boolean optThreaded = Utils.getFlag(FLAG_THREADED, options);
		String optVerbosity = Utils.getOption(FLAG_VERBOSITY, options);
		String optDumpModel = Utils.getOption(FLAG_DUMPMODEL, options);
		String optLoadModel = Utils.getOption(FLAG_LOADMODEL, options);
		String optThreshold = Utils.getOption(FLAG_THRESHOLD, options);
		String optPredictions = Utils.getOption(FLAG_PREDICTIONS, options);
		boolean optNoEval = Utils.getFlag(FLAG_NOEVAL, options);
		boolean optCrossvalidation = (Utils.getOptionPos(FLAG_CROSSVALIDATION, options) >= 0);
		String optCrossvalidationFolds = Utils.getOption(FLAG_CROSSVALIDATION, options);
		String optCrossvalidationOutDir = Utils.getOption(FLAG_CROSSVALIDATION_OUTDIR, options);
		String optSplitPercentage = Utils.getOption(FLAG_SPLITPERCENTAGE, options);
		String optSplitNumber = Utils.getOption(FLAG_SPLITNUMBER, options);
		boolean optInvertSplit = Utils.getFlag(FLAG_INVERTSPLIT, options);

		// use remaining options for classifier
		h.setOptions(options);

		if (h.getDebug()) System.out.println("Loading and preparing dataset ...");

		// Load Instances from a file
		Instances D_train = loadDataset(optTrainFile);

		Instances D_full = D_train;

		// Try extract and set a class index from the @relation name
		MLUtils.prepareData(D_train);

		// Override the number of classes with command-line option (optional)
		if(!optClasses.isEmpty()) {
			int L = Integer.parseInt(optClasses);
			D_train.setClassIndex(L);
		}

		// We we still haven't found -C option, we can't continue (don't know how many labels)
		int L = D_train.classIndex();
		if(L <= 0) {
			throw new Exception("[Error] Number of labels not specified.\n\tYou must set the number of labels with the -C option, either inside the @relation tag of the Instances file, or on the command line.");
			// apparently the dataset didn't contain the '-C' flag, check in the command line options ...
		}


		// Randomize (Instances) 
		int seed = (!optSeed.isEmpty()) ? Integer.parseInt(optSeed) : 0;
		if(optRandomize) {
			D_train.randomize(new Random(seed));
		}

		// Verbosity Option
		String voption = "1";
		if (!optVerbosity.isEmpty()) {
			voption = optVerbosity;
		}

		// Load from file?
		String lname = null;
		Instances dataHeader = null;
		if (!optLoadModel.isEmpty()) {
			Object[] data = SerializationHelper.readAll(optLoadModel);
			h = (MultiLabelClassifier)data[0];
			if (data.length > 1)
				dataHeader = (Instances) data[1];
		}

		try {

			Result r = null;

			// Threshold OPtion
			String top = "PCut1"; // default
			if (!optThreshold.isEmpty())
				top = optThreshold;

			// suppress evaluation?
			boolean doEval = !optNoEval;

			if(optCrossvalidation) {
				// CROSS-FOLD-VALIDATION

				// TODO output predictions?
				if (!optPredictions.isEmpty())
					System.err.println("Predictions cannot be saved when using cross-validation!");

				int numFolds = MLUtils.getIntegerOption(optCrossvalidationFolds,10); // default 10
				Map<Integer,Object[]> cvData = null;
				if (!optCrossvalidationOutDir.isEmpty()) {
					File dir = new File(optCrossvalidationOutDir);
					if (!dir.exists())
						throw new IOException("Cross-validation output directory (-" + FLAG_CROSSVALIDATION_OUTDIR + ") does not exist: " + optCrossvalidationOutDir);
					if (!dir.isDirectory())
						throw new IOException("Cross-validation output directory (-" + FLAG_CROSSVALIDATION_OUTDIR + ") does not point to a directory: " + optCrossvalidationOutDir);
					cvData = new HashMap<>();
				}
				r = Evaluation.cvModel(h,D_train,numFolds,top,voption, cvData);
				System.out.println(r.toString());
				// save per-fold data
				if (cvData != null) {
					Result.writeResultToFile(r, optCrossvalidationOutDir + "/results-cv.txt");
					for (int i = 0; i < numFolds; i++) {
						Object[] data = cvData.get(i);
						Instances train = (Instances) data[0];
						Instances test = (Instances) data[1];
						Result result = (Result) data[2];
						Instances performance = Result.getPredictionsAsInstances(result);
						DataSink.write(optCrossvalidationOutDir + "/train-" + i + ".arff", train);
						DataSink.write(optCrossvalidationOutDir + "/test-" + i + ".arff", test);
						DataSink.write(optCrossvalidationOutDir + "/predictions-" + i + ".arff", performance);
						Result.writeResultToFile(result, optCrossvalidationOutDir + "/results-" + i + ".txt");
					}
				}
			}
			else {
				// TRAIN-TEST SPLIT

				Instances D_test = null;

				if(!optTestFile.isEmpty()) {
					// load separate test set
					try {
						D_test = loadDataset(optTestFile);
						MLUtils.prepareData(D_test);
					} catch(Exception e) {
						throw new Exception("[Error] Failed to Load Test Instances from file.", e);
					}
				}
				else {
					// split training set into train and test sets
					// default split
					int N_T = (int)(D_train.numInstances() * 0.60);
					if(!optSplitPercentage.isEmpty()) {
						// split by percentage
						double percentTrain = Double.parseDouble(optSplitPercentage);
						N_T = (int)Math.round((D_train.numInstances() * (percentTrain/100.0)));
					}
					else if(!optSplitNumber.isEmpty()) {
						// split by number
						N_T = Integer.parseInt(optSplitNumber);
					}

					int N_t = D_train.numInstances() - N_T;
					D_test = new Instances(D_train,N_T,N_t);
					D_train = new Instances(D_train,0,N_T);
				}

				// Invert the split?
				if(optInvertSplit) {
					Instances temp = D_test;
					D_test = D_train;
					D_train = temp;
				}

				if (h.getDebug()) System.out.println(":- Dataset -: "+MLUtils.getDatasetName(D_train)+"\tL="+L+"\tD(t:T)=("+D_train.numInstances()+":"+D_test.numInstances()+")\tLC(t:T)="+Utils.roundDouble(MLUtils.labelCardinality(D_train,L),2)+":"+Utils.roundDouble(MLUtils.labelCardinality(D_test,L),2)+")");

				if (lname != null) {
					// h is already built, and loaded from a file, test it!
					if (doEval) {
						r = testClassifier(h, D_test);

						String t = top;

						if (top.startsWith("PCut")) {
							// if PCut is specified we need the training data,
							// so that we can calibrate the threshold!
							t = MLEvalUtils.getThreshold(r.predictions, D_train, top);
						}
						r = evaluateModel(h, D_test, t, voption);
					}
				}
				else {
					//check if train and test set size are > 0
					if(D_train.numInstances() > 0 &&
						D_test.numInstances() > 0){
						if (doEval) {
							if (optThreaded) {
								r = evaluateModelM(h, D_train, D_test, top, voption);
							}
							else {
								r = evaluateModel(h, D_train, D_test, top, voption);
							}
						}
						else {
							h.buildClassifier(D_train);
						}
					} else {
						// otherwise just train on full set. Maybe better throw an exception.
						h.buildClassifier(D_full);
					}
				}

				// @todo, if D_train==null, assume h is already trained
				if(D_train.numInstances() > 0 &&
					D_test.numInstances() > 0 &&
				    r != null) {
					System.out.println(r.toString());
				}

				// predictions
				if (!optPredictions.isEmpty()) {
					Instances predicted = new Instances(D_test, 0);
					for (int i = 0; i < D_test.numInstances(); i++) {
						double pred[] = h.distributionForInstance(D_test.instance(i));
						// Cut off any [no-longer-needed] probabalistic information from MT classifiers.
						if (h instanceof MultiTargetClassifier)
                                                    pred = Arrays.copyOfRange(pred, D_test.classIndex(), D_test.classIndex()*2);
						Instance predInst = (Instance) D_test.instance(i).copy();
						for (int j = 0; j < pred.length; j++)
							predInst.setValue(j, Math.round(pred[j])); // ML have probabilities; MT have discrete label indices
						predicted.add(predInst);
					}
					AbstractFileSaver saver = ConverterUtils.getSaverForFile(optPredictions);
					if (saver == null) {
						System.err.println("Failed to determine saver for '" + optPredictions + "', using " + ArffSaver.class.getName());
						saver = new ArffSaver();
					}
					saver.setFile(new File(optPredictions));
					saver.setInstances(predicted);
					saver.writeBatch();
					System.out.println("Predictions saved to: " + optPredictions);
				}
			}

			// Save model to file?
			if (!optDumpModel.isEmpty()) {
				dataHeader = new Instances(D_train, 0);
				SerializationHelper.writeAll(optDumpModel, new Object[]{h, dataHeader});
			}

		} catch(Exception e) {
			e.printStackTrace();
			Evaluation.printOptions(h.listOptions());
			System.exit(1);
		}

		System.exit(0);
	}


	/**
	 * IsMT - see if dataset D is multi-target (else only multi-label)
	 * @param	D	data
	 * @return	true iff D is multi-target only (else false)
	 */
	public static boolean isMT(Instances D) {
		int L = D.classIndex();
		for(int j = 0; j < L; j++) {
			if (D.attribute(j).isNominal()) {
				// Classification
				if (D.attribute(j).numValues() > 2) {
					// Multi-class
					return true;
				}
			}
			else {
				// Regression?
				System.err.println("[Warning] Found a non-nominal class -- not sure how this happened?");
			}
		}
		return false;
	}

	/**
	 * EvaluateModel - Build model 'h' on 'D_train', test it on 'D_test', threshold it according to 'top', using default verbosity option.
	 * @param	h		a multi-dim. classifier
	 * @param	D_train	training data
	 * @param	D_test 	test data
	 * @param	top    	Threshold OPtion (pertains to multi-label data only)
	 * @return	Result	raw prediction data with evaluation statistics included.
	 */
	public static Result evaluateModel(MultiXClassifier h, Instances D_train, Instances D_test, String top) throws Exception {
		return Evaluation.evaluateModel(h,D_train,D_test,top,"1");
	}

	/**
	 * EvaluateModel - Build model 'h' on 'D_train', test it on 'D_test', threshold it according to 'top', verbosity 'vop'.
	 * @param	h		a multi-dim. classifier
	 * @param	D_train	training data
	 * @param	D_test 	test data
	 * @param	top    	Threshold OPtion (pertains to multi-label data only)
	 * @param	vop    	Verbosity OPtion (which measures do we want to calculate/output)
	 * @return	Result	raw prediction data with evaluation statistics included.
	 */
	public static Result evaluateModel(MultiXClassifier h, Instances D_train, Instances D_test, String top, String vop) throws Exception {
		Result r = evaluateModel(h,D_train,D_test);
		if (h instanceof MultiTargetClassifier || isMT(D_test)) {
			r.setInfo("Type","MT");
		}
		else if (h instanceof MultiLabelClassifier) {
			r.setInfo("Type","ML");
			r.setInfo("Threshold",MLEvalUtils.getThreshold(r.predictions,D_train,top)); // <-- only relevant to ML (for now), but we'll put it in here in any case
		}
		r.setInfo("Verbosity",vop);
		r.output = Result.getStats(r, vop);
		return r;
	}

	/**
	 * EvaluateModel - Assume 'h' is already built, test it on 'D_test', threshold it according to 'top', verbosity 'vop'.
	 * @param	h		a multi-dim. classifier
	 * @param	D_test 	test data
	 * @param	tal    	Threshold VALUES (not option)
	 * @param	vop    	Verbosity OPtion (which measures do we want to calculate/output)
	 * @return	Result	raw prediction data with evaluation statistics included.
	 */
	public static Result evaluateModel(MultiXClassifier h, Instances D_test, String tal, String vop) throws Exception {
		Result r = testClassifier(h,D_test);
		if (h instanceof MultiTargetClassifier || isMT(D_test)) {
			r.setInfo("Type","MT");
		}
		else if (h instanceof MultiLabelClassifier) {
			r.setInfo("Type","ML");
		}
		r.setInfo("Threshold",tal);
		r.setInfo("Verbosity",vop);
		r.output = Result.getStats(r, vop);
		return r;
	}

	/**
	 * CVModel - Split D into train/test folds, and then train and evaluate on each one.
	 * @param	h		 a multi-output classifier
	 * @param	D      	 test data Instances
	 * @param	numFolds number of folds of CV
	 * @param	top    	 Threshold OPtion (pertains to multi-label data only)
	 * @return	Result	raw prediction data with evaluation statistics included.
	 */
	public static Result cvModel(MultiLabelClassifier h, Instances D, int numFolds, String top) throws Exception {
		return cvModel(h,D,numFolds,top,"1");
	}

	/**
	 * CVModel - Split D into train/test folds, and then train and evaluate on each one.
	 * @param	h		 a multi-output classifier
	 * @param	D      	 test data Instances
	 * @param	numFolds number of folds of CV
	 * @param	top    	 Threshold OPtion (pertains to multi-label data only)
	 * @param	vop    	Verbosity OPtion (which measures do we want to calculate/output)
	 * @return	Result	raw prediction data with evaluation statistics included.
	 */
	public static Result cvModel(MultiLabelClassifier h, Instances D, int numFolds, String top, String vop) throws Exception {
		return cvModel(h, D, numFolds, top, vop, null);
	}

	/**
	 * CVModel - Split D into train/test folds, and then train and evaluate on each one.
	 * @param	h		 a multi-output classifier
	 * @param	D      	 test data Instances
	 * @param	numFolds number of folds of CV
	 * @param	top    	 Threshold OPtion (pertains to multi-label data only)
	 * @param	vop    	Verbosity OPtion (which measures do we want to calculate/output)
	 * @param   perFold  the per fold data (0: train Instances, 1: test Instances, 2: Results), ignored if null
	 * @return	Result	raw prediction data with evaluation statistics included.
	 */
	public static Result cvModel(MultiLabelClassifier h, Instances D, int numFolds, String top, String vop, Map<Integer,Object[]> perFold) throws Exception {
		Result r_[] = new Result[numFolds];
		for(int i = 0; i < numFolds; i++) {
			Instances D_train = D.trainCV(numFolds,i);
			Instances D_test = D.testCV(numFolds,i);
			if (h.getDebug()) System.out.println(":- Fold ["+i+"/"+numFolds+"] -: "+MLUtils.getDatasetName(D)+"\tL="+D.classIndex()+"\tD(t:T)=("+D_train.numInstances()+":"+D_test.numInstances()+")\tLC(t:T)="+Utils.roundDouble(MLUtils.labelCardinality(D_train,D.classIndex()),2)+":"+Utils.roundDouble(MLUtils.labelCardinality(D_test,D.classIndex()),2)+")");
			r_[i] = evaluateModel(h, D_train, D_test); // <-- should not run stats yet!
			if (perFold != null)
				perFold.put(i, new Object[]{D_train, D_test, r_[i]});
		}
		Result r = MLEvalUtils.combinePredictions(r_);
		if (h instanceof MultiTargetClassifier || isMT(D)) {
			r.setInfo("Type","MT-CV");
		}
		else if (h instanceof MultiLabelClassifier) {
			r.setInfo("Type","ML-CV");
			try {
				r.setInfo("Threshold",String.valueOf(Double.parseDouble(top)));
			} catch(Exception e) {
				System.err.println("[WARNING] Automatic threshold calibration not currently enabled for cross-fold validation, setting threshold = 0.5.\n");
				r.setInfo("Threshold",String.valueOf(0.5));
			}
		}
		r.setInfo("Verbosity",vop);
		r.output = Result.getStats(r, vop);
		// Need to reset this because of CV
		r.setValue("Number of training instances",D.numInstances());
		r.setValue("Number of test instances",D.numInstances());
		return r;
	}

	/**
	 * EvaluateModel - Build model 'h' on 'D_train', test it on 'D_test'.
	 * Note that raw multi-label predictions returned in Result may not have been thresholded yet.
	 * However, data statistics, classifier info, and running times are inpregnated into the Result here.
	 * @param	h		a multi-dim. classifier
	 * @param	D_train	training data
	 * @param	D_test 	test data
	 * @return	raw prediction data (no evaluation yet)
	 */
	public static Result evaluateModel(MultiXClassifier h, Instances D_train, Instances D_test) throws Exception {

		long before = System.currentTimeMillis();
		// Set test data as unlabelled data, if SemisupervisedClassifier
		if (h instanceof SemisupervisedClassifier) {
			((SemisupervisedClassifier)h).introduceUnlabelledData(MLUtils.setLabelsMissing(new Instances(D_test)));
		}
		// Train
		h.buildClassifier(D_train);
		long after = System.currentTimeMillis();

		//System.out.println(":- Classifier -: "+h.getClass().getName()+": "+Arrays.toString(h.getOptions()));

		// Test
		long before_test = System.currentTimeMillis();
		Result result = testClassifier(h,D_test);
		long after_test = System.currentTimeMillis();

		result.setValue("Number of training instances",D_train.numInstances());
		result.setValue("Number of test instances",D_test.numInstances());
		result.setValue("Label cardinality (train set)",MLUtils.labelCardinality(D_train));
		result.setValue("Label cardinality (test set)",MLUtils.labelCardinality(D_test));

		result.setValue("Build Time",(after - before)/1000.0);
		result.setValue("Test Time",(after_test - before_test)/1000.0);
		result.setValue("Total Time", (after_test - before) / 1000.0);

		result.setInfo("Classifier",h.getClass().getName());
		result.setInfo("Options",Arrays.toString(h.getOptions()));
		result.setInfo("Additional Info",h.toString());
		result.setInfo("Dataset",MLUtils.getDatasetName(D_train));
		result.setInfo("Number of labels (L)",String.valueOf(D_train.classIndex()));
		//result.setInfo("Maxfreq_set",MLUtils.mostCommonCombination(D_train,result.L));

		String model = h.getModel();
		if (model.length() > 0)
			result.setModel("Model",h.getModel());

		return result;
	}

	/* allow threaded evaluation of model,
	 * all instances are passed to the classifier then they are gathered in results,
	 * for short datasets the overhead might be significant
	 */
	public static Result evaluateModelM(MultiXClassifier h, Instances D_train, Instances D_test, String top, String vop) throws Exception {
		// Train
		long before = System.currentTimeMillis();
				/*if (h instanceof SemisupervisedClassifier) { // *NEW* for semi-supervised 
					((SemisupervisedClassifier)h).setUnlabelledData(MLUtils.setLabelsMissing(new Instances(D_test)));
				}*/
		h.buildClassifier(D_train);
		long after = System.currentTimeMillis();

		//System.out.println(":- Classifier -: "+h.getClass().getName()+": "+Arrays.toString(h.getOptions()));

		// Test
		long before_test = System.currentTimeMillis();
		Result result = testClassifierM(h,D_test);
		long after_test = System.currentTimeMillis();

		result.setValue("N_train",D_train.numInstances());
		result.setValue("N_test",D_test.numInstances());
		result.setValue("LCard_train",MLUtils.labelCardinality(D_train));
		result.setValue("LCard_test",MLUtils.labelCardinality(D_test));

		result.setValue("Build_time",(after - before)/1000.0);
		result.setValue("Test_time",(after_test - before_test)/1000.0);
		result.setValue("Total_time",(after_test - before)/1000.0);

		result.setInfo("Classifier_name",h.getClass().getName());
		result.setInfo("Classifier_ops",Arrays.toString(h.getOptions()));
		result.setInfo("Classifier_info",h.toString());
		result.setInfo("Dataset_name",MLUtils.getDatasetName(D_train));
		//result.setInfo("Maxfreq_set",MLUtils.mostCommonCombination(D_train,result.L));

		if (h instanceof MultiTargetClassifier || isMT(D_test)) {
			result.setInfo("Type","MT");
		}
		else if (h instanceof MultiLabelClassifier) {
			result.setInfo("Type","ML");
		}
		result.setInfo("Threshold",MLEvalUtils.getThreshold(result.predictions,D_train,top)); // <-- only relevant to ML (for now), but we'll put it in here in any case
		result.setInfo("Verbosity",vop);
		result.output = Result.getStats(result, vop);
		return result;
	}

	/**
	 * TestClassifier - test classifier h on D_test
	 * @param	h		a multi-dim. classifier, ALREADY BUILT
	 * @param	D_test 	test data
	 * @return	Result	with raw prediction data ONLY
	 */
	public static Result testClassifier(MultiXClassifier h, Instances D_test) throws Exception {

		int L = D_test.classIndex();
		Result result = new Result(D_test.numInstances(),L);

		if(h.getDebug()) System.out.print(":- Evaluate ");
		for (int i = 0, c = 0; i < D_test.numInstances(); i++) {

			if(h.getDebug()) { int t = i*50/D_test.numInstances(); if(t > c) { System.out.print("#"); c = t; } }

			// No cheating allowed; clear all class information
			Instance x = (Instance)(D_test.instance(i)).copy();
			for(int v = 0; v < D_test.classIndex(); v++)
				x.setValue(v,0.0);

			// Get and store ranking
			double y[] = h.distributionForInstance(x);
			// Cut off any [no-longer-needed] probabalistic information from MT classifiers.
			if (h instanceof MultiTargetClassifier)
                            y = Arrays.copyOfRange(y, L, L*2);

			// Store the result
			result.addResult(y,D_test.instance(i));
		}
		if(h.getDebug()) System.out.println(":-");

		/*
		if(h.getDebug()) {

			for(int i = 0; i < result.size(); i++) {
				System.out.println("\t"+Arrays.toString(result.rowTrue(i))+" vs "+Arrays.toString(result.rowRanking(i)));
			}
		}
		*/

		return result;
	}
	/**
	 *Test Classifier but threaded (Multiple)
	 * @param	h		a multi-dim. classifier, ALREADY BUILT (threaded, implements MultiLabelThreaded)
	 * @param	D_test 	test data
	 * @return	Result	with raw prediction data ONLY
	 */
	public static Result testClassifierM(MultiXClassifier h, Instances D_test) throws Exception {

		int L = D_test.classIndex();
		Result result = new Result(D_test.numInstances(),L);
		if(h.getDebug()) System.out.print(":- Evaluate ");
		if(h instanceof MultiLabelClassifierThreaded){
			((MultiLabelClassifierThreaded)h).setThreaded(true);
			double y[][] = ((MultiLabelClassifierThreaded)h).distributionForInstanceM(D_test);

			for (int i = 0, c = 0; i < D_test.numInstances(); i++) {
				// Store the result
				result.addResult(y[i],D_test.instance(i));
			}
			if(h.getDebug()) System.out.println(":-");

		/*
		if(h.getDebug()) {

			for(int i = 0; i < result.size(); i++) {
				System.out.println("\t"+Arrays.toString(result.rowActual(i))+" vs "+Arrays.toString(result.rowRanking(i)));
			}


		}
		*/
		}
		return result;
	}

	/**
	 * GetDataset - load a dataset, given command line options specifying an arff file, and set the class index correctly to indicate the number of labels.
	 * @param	options	command line options
	 * @param	T		set to 'T' if we want to load a test file
	 * @return	An Instances representing the dataset
	public static Instances getDataset(String options[], char T) throws Exception {
	Instances D = loadDataset(options, T);
	setClassesFromOptions(D,MLUtils.getDatasetOptions(D));
	return D;
	}
	 */

	/**
	 * GetDataset - load a dataset, given command line options specifying an arff file, and set the class index correctly to indicate the number of labels.
	 * @param	options	command line options
	 * @return	An Instances representing the dataset
	public static Instances getDataset(String options[]) throws Exception {
	return getDataset(options,'t');
	}
	 */

	/**
	 * loadDataset - load a dataset, given command line option '-t' specifying an arff file.
	 * @param	options	command line options, specifying dataset filename
	 * @return	the dataset
	 */
	public static Instances loadDataset(String options[]) throws Exception {
		return loadDataset(options, FLAG_TRAINFILE);
	}

	/**
	 * loadDataset - load a dataset, given command line options specifying an arff file.
	 * @param	options	command line options, specifying dataset filename
	 * @param	T		set to 'T' if we want to load a test file (default 't': load train or train-test file)
	 * @return	the dataset
	 */
	public static Instances loadDataset(String options[], char T) throws Exception {
		return loadDataset(Utils.getOption(T, options));
	}

	/**
	 * loadDataset - load a dataset, given command line options specifying an arff file.
	 * @param	filename	the filename to load
	 * @return	the dataset
	 */
	public static Instances loadDataset(String filename) throws Exception {

		Instances D = null;

		// Check for filename
		if (filename == null || filename.isEmpty())
			throw new Exception("[Error] You did not specify a dataset!");

		// Check for existence of file
		File file = new File(filename);
		if (!file.exists())
			throw new Exception("[Error] File does not exist: " + filename);
		if (file.isDirectory())
			throw new Exception("[Error] "+filename+ " points to a directory!");

		try {
			DataSource source = new DataSource(filename);
			D = source.getDataSet();
		} catch(Exception e) {
			e.printStackTrace();
			throw new Exception("[Error] Failed to load Instances from file '"+filename+"'.");
		}

		return D;
	}

	/*
	 * GetL - get number of labels (option 'C' from options 'options').
	private static int getL(String options[]) throws Exception {
		return (Utils.getOptionPos('C', options) >= 0) ? Integer.parseInt(Utils.getOption('C',options)) : 0;
	}
	*/
	

	/*
	 * SetClassesFromOptions - set the class index correctly in a dataset 'D', given command line options 'options'.
	 * <br>
	 * NOTE: there is a similar function in Exlorer.prepareData(D) but that function can only take -C from the dataset options.
	 * <br>
	 * TODO: replace the call to Exlorer.prepareData(D) with this method here (use the name 'prepareData' -- it souds better).
	public static void setClassesFromOptions(Instances D, String options[]) throws Exception {
		try {
			// get L
			int L = getL(options);
			// if negative, then invert first
			if ( L < 0) {
				L = -L;
				D = F.mulan2meka(D,L);
			}
			// set L
			D.setClassIndex(L);
		} catch(Exception e) {
			e.printStackTrace();
			throw new Exception ("[Error] Failed to Set Classes from options. You must supply the number of labels either in the @Relation Name of the dataset or on the command line using the option: -C <num. labels>");
		}
	}
	*/

	public static void printOptions(Enumeration e) {

		// Evaluation Options
		StringBuffer text = new StringBuffer();
		text.append("\n\nEvaluation Options:\n\n");
		text.append("-" + FLAG_HELP + "\n");
		text.append("\tOutput help information.\n");
		text.append("-" + FLAG_TRAINFILE + " <name of training file>\n");
		text.append("\tSets training file.\n");
		text.append("-" + FLAG_TESTFILE + " <name of test file>\n");
		text.append("\tSets test file (will be used for making predictions).\n");
		text.append("-" + FLAG_PREDICTIONS + " <name of output file for predictions>\n");
		text.append("\tSets the file to store the predictions in (does not work with cross-validation).\n");
		text.append("-" + FLAG_CROSSVALIDATION + " <number of folds>\n");
		text.append("\tDo cross-validation with this many folds.\n");
		text.append("-" + FLAG_CROSSVALIDATION_OUTDIR + " <dir>\n");
		text.append("\tOptional (existing) directory for storing cross-validation output per fold\n\t(train, test, performance, results).\n");
		text.append("-" + FLAG_NOEVAL + "\n");
		text.append("\tSkips evaluation, e.g., used when test set contains no class labels.\n");
		text.append("-" + FLAG_RANDOMIZE + "\n");
		text.append("\tRandomize the order of instances in the dataset.\n");
		text.append("-" + FLAG_SPLITPERCENTAGE + " <percentage>\n");
		text.append("\tSets the percentage for the train/test set split, e.g., 66.\n");
		text.append("-" + FLAG_SPLITNUMBER + " <number>\n");
		text.append("\tSets the number of training examples, e.g., 800\n");
		text.append("-" + FLAG_INVERTSPLIT + "\n");
		text.append("\tInvert the specified train/test split.\n");
		text.append("-" + FLAG_SEED + " <random number seed>\n");
		text.append("\tSets random number seed (use with -R, for different CV or train/test splits).\n");
		text.append("-" + FLAG_THRESHOLD + " <threshold>\n");
		text.append("\tSets the type of thresholding; where\n\t\t'PCut1' automatically calibrates a threshold (the default);\n\t\t'PCutL' automatically calibrates one threshold for each label;\n\t\tany number, e.g. '0.5', specifies that threshold.\n");
		text.append("-" + FLAG_CLASSES + " <number of labels>\n");
		text.append("\tSets the number of target variables (labels) to assume (indexed from the beginning).\n");
		text.append("-" + FLAG_DUMPMODEL + " <classifier_file>\n");
		text.append("\tSpecify a file to dump classifier into.\n");
		text.append("-" + FLAG_LOADMODEL + " <classifier_file>\n");
		text.append("\tSpecify a file to load classifier from.\n");
		text.append("-" + FLAG_VERBOSITY + " <verbosity level>\n");
		text.append("\tSpecify more/less evaluation output\n");
		// Multilabel Options
		text.append("\n\nClassifier Options:\n\n");
		while (e.hasMoreElements()) {
			Option o = (Option) (e.nextElement());
			text.append("-"+o.name()+'\n');
			text.append(""+o.description()+'\n');
		}

		System.out.println(text);
	}

}
