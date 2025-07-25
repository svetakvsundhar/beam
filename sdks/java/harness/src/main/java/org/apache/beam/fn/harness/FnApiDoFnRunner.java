/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.fn.harness;

import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkArgument;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkNotNull;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkState;

import com.google.auto.service.AutoService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.beam.fn.harness.control.BundleProgressReporter;
import org.apache.beam.fn.harness.control.BundleSplitListener;
import org.apache.beam.fn.harness.state.FnApiStateAccessor;
import org.apache.beam.fn.harness.state.FnApiTimerBundleTracker;
import org.apache.beam.fn.harness.state.FnApiTimerBundleTracker.Modifications;
import org.apache.beam.fn.harness.state.FnApiTimerBundleTracker.TimerInfo;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.BundleApplication;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.DelayedBundleApplication;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.model.pipeline.v1.RunnerApi.PCollection;
import org.apache.beam.model.pipeline.v1.RunnerApi.PTransform;
import org.apache.beam.model.pipeline.v1.RunnerApi.ParDoPayload;
import org.apache.beam.model.pipeline.v1.RunnerApi.TimerFamilySpec;
import org.apache.beam.runners.core.DoFnRunner;
import org.apache.beam.runners.core.LateDataUtils;
import org.apache.beam.runners.core.metrics.MonitoringInfoConstants;
import org.apache.beam.runners.core.metrics.ShortIdMap;
import org.apache.beam.runners.core.metrics.SimpleMonitoringInfoBuilder;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.DoubleCoder;
import org.apache.beam.sdk.coders.IterableCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.fn.data.FnDataReceiver;
import org.apache.beam.sdk.fn.splittabledofn.RestrictionTrackers;
import org.apache.beam.sdk.fn.splittabledofn.RestrictionTrackers.ClaimObserver;
import org.apache.beam.sdk.fn.splittabledofn.WatermarkEstimators;
import org.apache.beam.sdk.function.ThrowingRunnable;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.schemas.SchemaCoder;
import org.apache.beam.sdk.state.ReadableState;
import org.apache.beam.sdk.state.State;
import org.apache.beam.sdk.state.StateSpec;
import org.apache.beam.sdk.state.TimeDomain;
import org.apache.beam.sdk.state.TimerMap;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.BundleFinalizer;
import org.apache.beam.sdk.transforms.DoFn.MultiOutputReceiver;
import org.apache.beam.sdk.transforms.DoFn.OutputReceiver;
import org.apache.beam.sdk.transforms.DoFnSchemaInformation;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.reflect.DoFnInvoker;
import org.apache.beam.sdk.transforms.reflect.DoFnInvoker.BaseArgumentProvider;
import org.apache.beam.sdk.transforms.reflect.DoFnInvoker.DelegatingArgumentProvider;
import org.apache.beam.sdk.transforms.reflect.DoFnInvokers;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.StateDeclaration;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.TimerFamilyDeclaration;
import org.apache.beam.sdk.transforms.reflect.DoFnSignatures;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker.HasProgress;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker.Progress;
import org.apache.beam.sdk.transforms.splittabledofn.SplitResult;
import org.apache.beam.sdk.transforms.splittabledofn.TimestampObservingWatermarkEstimator;
import org.apache.beam.sdk.transforms.splittabledofn.WatermarkEstimator;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.util.ByteStringOutputStream;
import org.apache.beam.sdk.util.UserCodeException;
import org.apache.beam.sdk.util.construction.PTransformTranslation;
import org.apache.beam.sdk.util.construction.ParDoTranslation;
import org.apache.beam.sdk.util.construction.RehydratedComponents;
import org.apache.beam.sdk.util.construction.Timer;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.WindowedValue;
import org.apache.beam.sdk.values.WindowedValues;
import org.apache.beam.sdk.values.WindowedValues.WindowedValueCoder;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.apache.beam.vendor.grpc.v1p69p0.com.google.protobuf.ByteString;
import org.apache.beam.vendor.grpc.v1p69p0.com.google.protobuf.util.Durations;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.annotations.VisibleForTesting;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableMap;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Iterables;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Maps;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Sets;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Table;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.DateTimeUtils;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.joda.time.format.PeriodFormat;

/**
 * A {@link DoFnRunner} specific to integrating with the Fn Api. This is to remove the layers of
 * abstraction caused by StateInternals/TimerInternals since they model state and timer concepts
 * differently.
 */
@SuppressWarnings({
  "rawtypes" // TODO(https://github.com/apache/beam/issues/20447)
})
@Internal
public class FnApiDoFnRunner<InputT, RestrictionT, PositionT, WatermarkEstimatorStateT, OutputT>
    implements FnApiStateAccessor.MutatingStateContext<Object, BoundedWindow> {
  /** A registrar which provides a factory to handle Java {@link DoFn}s. */
  @AutoService(PTransformRunnerFactory.Registrar.class)
  public static class Registrar implements PTransformRunnerFactory.Registrar {
    @Override
    public Map<String, PTransformRunnerFactory> getPTransformRunnerFactories() {
      Factory factory = new Factory();
      return ImmutableMap.<String, PTransformRunnerFactory>builder()
          .put(PTransformTranslation.PAR_DO_TRANSFORM_URN, factory)
          .put(
              PTransformTranslation.SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN, factory)
          .build();
    }
  }

  static class Factory<InputT, RestrictionT, PositionT, WatermarkEstimatorStateT, OutputT>
      implements PTransformRunnerFactory {

    @Override
    public final void addRunnerForPTransform(Context context) throws IOException {

      FnApiStateAccessor<Object> stateAccessor =
          FnApiStateAccessor.Factory.factoryForPTransformContext(context).create();

      FnApiDoFnRunner<InputT, RestrictionT, PositionT, WatermarkEstimatorStateT, OutputT> runner =
          new FnApiDoFnRunner<>(
              context.getPipelineOptions(),
              context.getShortIdMap(),
              context.getPTransformId(),
              context.getPTransform(),
              context.getComponents(),
              context::addStartBundleFunction,
              context::addFinishBundleFunction,
              context::addResetFunction,
              context::addTearDownFunction,
              context::getPCollectionConsumer,
              context::addPCollectionConsumer,
              context::addOutgoingTimersEndpoint,
              context::addBundleProgressReporter,
              context.getSplitListener(),
              context.getBundleFinalizer(),
              stateAccessor);

      stateAccessor.setKeyAndWindowContext(runner);

      for (Map.Entry<String, KV<TimeDomain, Coder<Timer<Object>>>> entry :
          runner.timerFamilyInfos.entrySet()) {
        String localName = entry.getKey();
        TimeDomain timeDomain = entry.getValue().getKey();
        Coder<Timer<Object>> coder = entry.getValue().getValue();
        if (!localName.equals("")
            && localName.equals(runner.parDoPayload.getOnWindowExpirationTimerFamilySpec())) {
          context.addIncomingTimerEndpoint(localName, coder, runner::processOnWindowExpiration);
        } else {
          context.addIncomingTimerEndpoint(
              localName, coder, timer -> runner.processTimer(localName, timeDomain, timer));
        }
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////

  private final PipelineOptions pipelineOptions;
  private final String pTransformId;
  private final PTransform pTransform;
  private final DoFn<InputT, OutputT> doFn;
  private final DoFnSignature doFnSignature;
  private final TupleTag<OutputT> mainOutputTag;
  private final Coder<?> inputCoder;

  private final SchemaCoder<OutputT> mainOutputSchemaCoder;
  private final Coder<? extends BoundedWindow> windowCoder;
  private final Map<TupleTag<?>, Coder<?>> outputCoders;
  private final Map<String, KV<TimeDomain, Coder<Timer<Object>>>> timerFamilyInfos;
  private final ParDoPayload parDoPayload;
  private final Map<String, FnDataReceiver<WindowedValue<?>>> localNameToConsumer;
  private final BundleSplitListener splitListener;
  private final BundleFinalizer bundleFinalizer;
  private final FnDataReceiver<WindowedValue<OutputT>> mainOutputConsumer;

  private final String mainInputId;
  private final FnApiStateAccessor<?> stateAccessor;
  private final Map<String, FnDataReceiver<?>> outboundTimerReceivers;
  private final @Nullable FnApiTimerBundleTracker timerBundleTracker;
  private final DoFnInvoker<InputT, OutputT> doFnInvoker;
  private final StartBundleArgumentProvider startBundleArgumentProvider;
  private final ProcessBundleContextBase processContext;
  private final OnTimerContext<?> onTimerContext;
  private final OnWindowExpirationContext<?> onWindowExpirationContext;
  private final FinishBundleArgumentProvider finishBundleArgumentProvider;
  private final Duration allowedLateness;
  private final String workCompletedShortId;
  private final String workRemainingShortId;

  /**
   * Used to guarantee a consistent view of this {@link FnApiDoFnRunner} while setting up for {@link
   * DoFnInvoker#invokeProcessElement} since {@link #trySplitForElementAndRestriction} may access
   * internal {@link FnApiDoFnRunner} state concurrently.
   */
  private final Object splitLock = new Object();

  private final DoFnSchemaInformation doFnSchemaInformation;
  private final Map<String, PCollectionView<?>> sideInputMapping;

  // The member variables below are only valid for the lifetime of certain methods.
  /** Only valid during {@code processElement...} methods, null otherwise. */
  private WindowedValue<InputT> currentElement;

  private Object currentKey;

  /**
   * Only valid during {@link
   * #processElementForWindowObservingSizedElementAndRestriction(WindowedValue)}.
   */
  private List<BoundedWindow> currentWindows;

  /**
   * The window index at which processing should stop. The window with this index should not be
   * processed.
   *
   * <p>Only valid during {@link
   * #processElementForWindowObservingSizedElementAndRestriction(WindowedValue)}.
   */
  private int windowStopIndex;

  /**
   * The window index which is currently being processed. This should always be less than
   * windowStopIndex.
   *
   * <p>Only valid during {@link
   * #processElementForWindowObservingSizedElementAndRestriction(WindowedValue)}.
   */
  private int windowCurrentIndex;

  /** Only valid during #processElementForWindowObservingSizedElementAndRestriction}. */
  private RestrictionT currentRestriction;

  /** Only valid during {@link #processElementForWindowObservingSizedElementAndRestriction}. */
  private WatermarkEstimatorStateT currentWatermarkEstimatorState;

  /** Only valid during {@link #processElementForWindowObservingSizedElementAndRestriction}. */
  private Instant initialWatermark;

  /**
   * Only valid during {@link #processElementForWindowObservingSizedElementAndRestriction}, null
   * otherwise.
   */
  private WatermarkEstimators.WatermarkAndStateObserver<WatermarkEstimatorStateT>
      currentWatermarkEstimator;

  /**
   * Only valid during {@code processElementForWindowObserving...} and {@link #processTimer}
   * methods, null otherwise.
   */
  private BoundedWindow currentWindow;

  /**
   * Only valid during {@link #processElementForWindowObservingSizedElementAndRestriction}, null
   * otherwise.
   */
  private RestrictionTracker<RestrictionT, PositionT> currentTracker;
  /**
   * If non-null, set to true after currentTracker has had a tryClaim issued on it. Used to ignore
   * checkpoint split requests if no progress was made.
   */
  private @Nullable AtomicBoolean currentTrackerClaimed;

  /**
   * Only valid during {@link #processTimer} and {@link #processOnWindowExpiration}, null otherwise.
   */
  private Timer<?> currentTimer;

  /** Only valid during {@link #processTimer}, null otherwise. */
  private TimeDomain currentTimeDomain;

  FnApiDoFnRunner(
      PipelineOptions pipelineOptions,
      ShortIdMap shortIds,
      String pTransformId,
      PTransform pTransform,
      RunnerApi.Components components,
      Consumer<ThrowingRunnable> addStartFunction,
      Consumer<ThrowingRunnable> addFinishFunction,
      Consumer<ThrowingRunnable> addResetFunction,
      Consumer<ThrowingRunnable> addTearDownFunction,
      Function<String, FnDataReceiver<WindowedValue<?>>> getPCollectionConsumer,
      BiConsumer<String, FnDataReceiver> addPCollectionConsumer,
      BiFunction<String, Coder<Timer<Object>>, FnDataReceiver<Timer<Object>>>
          getOutgoingTimersConsumer,
      Consumer<BundleProgressReporter> addBundleProgressReporter,
      BundleSplitListener splitListener,
      BundleFinalizer bundleFinalizer,
      FnApiStateAccessor<Object> stateAccessor) {
    this.pipelineOptions = pipelineOptions;
    this.pTransformId = pTransformId;
    this.pTransform = pTransform;
    Coder<?> keyCoder;
    try {
      RehydratedComponents rehydratedComponents =
          RehydratedComponents.forComponents(components).withPipeline(Pipeline.create());
      parDoPayload = ParDoPayload.parseFrom(pTransform.getSpec().getPayload());
      doFn = (DoFn) ParDoTranslation.getDoFn(parDoPayload);
      doFnSignature = DoFnSignatures.signatureForDoFn(doFn);
      switch (pTransform.getSpec().getUrn()) {
        case PTransformTranslation.SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN:
        case PTransformTranslation.PAR_DO_TRANSFORM_URN:
          mainOutputTag = (TupleTag) ParDoTranslation.getMainOutputTag(parDoPayload);
          break;
        case PTransformTranslation.SPLITTABLE_SPLIT_AND_SIZE_RESTRICTIONS_URN:
          mainOutputTag =
              new TupleTag(Iterables.getOnlyElement(pTransform.getOutputsMap().keySet()));
          break;
        default:
          throw new IllegalStateException(
              String.format("Unknown urn: %s", pTransform.getSpec().getUrn()));
      }
      String mainInputTag =
          Iterables.getOnlyElement(
              Sets.difference(
                  pTransform.getInputsMap().keySet(), parDoPayload.getSideInputsMap().keySet()));
      PCollection mainInput =
          components.getPcollectionsMap().get(pTransform.getInputsOrThrow(mainInputTag));
      Coder<?> maybeWindowedValueInputCoder = rehydratedComponents.getCoder(mainInput.getCoderId());
      // TODO: Stop passing windowed value coders within PCollections.
      if (maybeWindowedValueInputCoder instanceof WindowedValues.WindowedValueCoder) {
        inputCoder = ((WindowedValueCoder) maybeWindowedValueInputCoder).getValueCoder();
      } else {
        inputCoder = maybeWindowedValueInputCoder;
      }
      if (inputCoder instanceof KvCoder) {
        keyCoder = ((KvCoder) inputCoder).getKeyCoder();
      } else {
        keyCoder = null;
      }

      WindowingStrategy<InputT, ?> windowingStrategy =
          (WindowingStrategy)
              rehydratedComponents.getWindowingStrategy(mainInput.getWindowingStrategyId());
      windowCoder = windowingStrategy.getWindowFn().windowCoder();

      outputCoders = Maps.newHashMap();
      for (Map.Entry<String, String> entry : pTransform.getOutputsMap().entrySet()) {
        TupleTag<?> outputTag = new TupleTag<>(entry.getKey());
        RunnerApi.PCollection outputPCollection =
            components.getPcollectionsMap().get(entry.getValue());
        Coder<?> outputCoder = rehydratedComponents.getCoder(outputPCollection.getCoderId());
        if (outputCoder instanceof WindowedValueCoder) {
          outputCoder = ((WindowedValueCoder) outputCoder).getValueCoder();
        }
        outputCoders.put(outputTag, outputCoder);
      }
      Coder<OutputT> outputCoder = (Coder<OutputT>) outputCoders.get(mainOutputTag);
      mainOutputSchemaCoder =
          (outputCoder instanceof SchemaCoder) ? (SchemaCoder<OutputT>) outputCoder : null;

      ImmutableMap.Builder<String, KV<TimeDomain, Coder<Timer<Object>>>> timerFamilyInfosBuilder =
          ImmutableMap.builder();

      // Extract out relevant TimerFamilySpec information in preparation for execution.
      for (Map.Entry<String, TimerFamilySpec> entry :
          parDoPayload.getTimerFamilySpecsMap().entrySet()) {
        // The timer family spec map key is either from timerId of timer declaration or
        // timerFamilyId from timer family declaration.
        String timerIdOrTimerFamilyId = entry.getKey();
        TimeDomain timeDomain = translateTimeDomain(entry.getValue().getTimeDomain());
        Coder<Timer<Object>> timerCoder =
            (Coder) rehydratedComponents.getCoder(entry.getValue().getTimerFamilyCoderId());
        timerFamilyInfosBuilder.put(timerIdOrTimerFamilyId, KV.of(timeDomain, timerCoder));
      }
      timerFamilyInfos = timerFamilyInfosBuilder.build();

      this.mainInputId = ParDoTranslation.getMainInputName(pTransform);
      this.allowedLateness =
          rehydratedComponents
              .getPCollection(pTransform.getInputsOrThrow(mainInputId))
              .getWindowingStrategy()
              .getAllowedLateness();

    } catch (IOException exn) {
      throw new IllegalArgumentException("Malformed ParDoPayload", exn);
    }

    ImmutableMap.Builder<String, FnDataReceiver<WindowedValue<?>>> localNameToConsumerBuilder =
        ImmutableMap.builder();
    for (Map.Entry<String, String> entry : pTransform.getOutputsMap().entrySet()) {
      localNameToConsumerBuilder.put(
          entry.getKey(), getPCollectionConsumer.apply(entry.getValue()));
    }
    localNameToConsumer = localNameToConsumerBuilder.build();
    this.splitListener = splitListener;
    this.bundleFinalizer = bundleFinalizer;
    this.onTimerContext = new OnTimerContext();
    this.onWindowExpirationContext = new OnWindowExpirationContext<>();

    this.mainOutputConsumer =
        (FnDataReceiver<WindowedValue<OutputT>>)
            (FnDataReceiver) localNameToConsumer.get(mainOutputTag.getId());
    this.doFnSchemaInformation = ParDoTranslation.getSchemaInformation(parDoPayload);
    this.sideInputMapping = ParDoTranslation.getSideInputMapping(parDoPayload);
    this.doFnInvoker = DoFnInvokers.tryInvokeSetupFor(doFn, pipelineOptions);

    this.startBundleArgumentProvider = new StartBundleArgumentProvider();
    // Register the appropriate handlers.
    switch (pTransform.getSpec().getUrn()) {
      case PTransformTranslation.PAR_DO_TRANSFORM_URN:
      case PTransformTranslation.SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN:
        addStartFunction.accept(this::startBundle);
        break;
      case PTransformTranslation.SPLITTABLE_SPLIT_AND_SIZE_RESTRICTIONS_URN:
        // startBundle should not be invoked
      default:
        // no-op
    }

    String mainInput;
    try {
      mainInput = ParDoTranslation.getMainInputName(pTransform);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    final FnDataReceiver<WindowedValue> mainInputConsumer;
    switch (pTransform.getSpec().getUrn()) {
      case PTransformTranslation.PAR_DO_TRANSFORM_URN:
        if (doFnSignature.processElement().observesWindow() || !sideInputMapping.isEmpty()) {
          mainInputConsumer = this::processElementForWindowObservingParDo;
          this.processContext = new WindowObservingProcessBundleContext();
        } else {
          mainInputConsumer = this::processElementForParDo;
          this.processContext = new NonWindowObservingProcessBundleContext();
        }
        break;
      case PTransformTranslation.SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN:
        if (doFnSignature.processElement().observesWindow()
            || (doFnSignature.newTracker() != null && doFnSignature.newTracker().observesWindow())
            || (doFnSignature.getSize() != null && doFnSignature.getSize().observesWindow())
            || (doFnSignature.newWatermarkEstimator() != null
                && doFnSignature.newWatermarkEstimator().observesWindow())
            || !sideInputMapping.isEmpty()) {
          mainInputConsumer =
              new SplittableFnDataReceiver() {
                @Override
                public void accept(WindowedValue input) throws Exception {
                  processElementForWindowObservingSizedElementAndRestriction(input);
                }
              };
          this.processContext = new WindowObservingProcessBundleContext();
        } else {
          mainInputConsumer =
              new SplittableFnDataReceiver() {
                @Override
                public void accept(WindowedValue input) throws Exception {
                  // TODO(BEAM-10303): Create a variant which is optimized to not observe the
                  // windows.
                  processElementForWindowObservingSizedElementAndRestriction(input);
                }
              };
          this.processContext = new WindowObservingProcessBundleContext();
        }
        break;
      default:
        throw new IllegalStateException("Unknown urn: " + pTransform.getSpec().getUrn());
    }
    addPCollectionConsumer.accept(pTransform.getInputsOrThrow(mainInput), mainInputConsumer);

    this.finishBundleArgumentProvider = new FinishBundleArgumentProvider();
    switch (pTransform.getSpec().getUrn()) {
      case PTransformTranslation.PAR_DO_TRANSFORM_URN:
      case PTransformTranslation.SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN:
        addFinishFunction.accept(this::finishBundle);
        break;
      case PTransformTranslation.SPLITTABLE_SPLIT_AND_SIZE_RESTRICTIONS_URN:
        // finishBundle should not be invoked
      default:
        // no-op
    }
    addTearDownFunction.accept(this::tearDown);

    workCompletedShortId =
        shortIds.getOrCreateShortId(
            new SimpleMonitoringInfoBuilder()
                .setUrn(MonitoringInfoConstants.Urns.WORK_COMPLETED)
                .setType(MonitoringInfoConstants.TypeUrns.PROGRESS_TYPE)
                .setLabel(MonitoringInfoConstants.Labels.PTRANSFORM, pTransformId)
                .build());
    workRemainingShortId =
        shortIds.getOrCreateShortId(
            new SimpleMonitoringInfoBuilder()
                .setUrn(MonitoringInfoConstants.Urns.WORK_REMAINING)
                .setType(MonitoringInfoConstants.TypeUrns.PROGRESS_TYPE)
                .setLabel(MonitoringInfoConstants.Labels.PTRANSFORM, pTransformId)
                .build());
    switch (pTransform.getSpec().getUrn()) {
      case PTransformTranslation.SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN:
        addBundleProgressReporter.accept(
            new BundleProgressReporter() {

              @Override
              public void updateIntermediateMonitoringData(Map<String, ByteString> monitoringData) {
                Progress progress = getProgress();
                if (progress == null) {
                  return;
                }

                ByteString encodedWorkCompleted, encodedWorkRemaining;
                try {
                  encodedWorkCompleted = encodeProgress(progress.getWorkCompleted());
                  encodedWorkRemaining = encodeProgress(progress.getWorkRemaining());
                } catch (IOException e) {
                  throw new RuntimeException("Failed to encode progress", e);
                }
                monitoringData.put(workCompletedShortId, encodedWorkCompleted);
                monitoringData.put(workRemainingShortId, encodedWorkRemaining);
              }

              @Override
              public void updateFinalMonitoringData(Map<String, ByteString> monitoringData) {
                // No elements will be inflight when the progress completes.
              }

              @Override
              public void reset() {}

              private ByteString encodeProgress(double value) throws IOException {
                ByteStringOutputStream output = new ByteStringOutputStream();
                IterableCoder.of(DoubleCoder.of()).encode(Arrays.asList(value), output);
                return output.toByteString();
              }
            });
        break;
      default:
        // no-op
    }

    this.stateAccessor = stateAccessor;

    // Register as a consumer for each timer.
    this.outboundTimerReceivers = new HashMap<>();
    if (timerFamilyInfos.isEmpty()) {
      this.timerBundleTracker = null;
    } else {
      this.timerBundleTracker =
          new FnApiTimerBundleTracker(
              keyCoder, windowCoder, this::getCurrentKey, () -> currentWindow);
      addResetFunction.accept(timerBundleTracker::reset);

      for (Map.Entry<String, KV<TimeDomain, Coder<Timer<Object>>>> timerFamilyInfo :
          timerFamilyInfos.entrySet()) {
        String localName = timerFamilyInfo.getKey();
        Coder<Timer<Object>> timerCoder = timerFamilyInfo.getValue().getValue();
        outboundTimerReceivers.put(
            localName, getOutgoingTimersConsumer.apply(localName, timerCoder));
      }
    }
  }

  @Override
  public Object getCurrentKey() {
    if (currentKey != null) {
      return currentKey;
    }
    // TODO: Maybe memoize the key?
    if (currentElement != null) {
      checkState(
          currentElement.getValue() instanceof KV,
          "Accessing state in unkeyed context. Current element is not a KV: %s.",
          currentElement.getValue());
      return ((KV) currentElement.getValue()).getKey();
    } else if (currentTimer != null) {
      return currentTimer.getUserKey();
    }
    return null;
  }

  @Override
  public BoundedWindow getCurrentWindow() {
    return this.currentWindow;
  }

  private void startBundle() {
    doFnInvoker.invokeStartBundle(startBundleArgumentProvider);
  }

  private void processElementForParDo(WindowedValue<InputT> elem) {
    currentElement = elem;
    try {
      doFnInvoker.invokeProcessElement(processContext);
    } finally {
      currentElement = null;
    }
  }

  private void processElementForWindowObservingParDo(WindowedValue<InputT> elem) {
    currentElement = elem;
    try {
      Iterator<BoundedWindow> windowIterator =
          (Iterator<BoundedWindow>) elem.getWindows().iterator();
      while (windowIterator.hasNext()) {
        currentWindow = windowIterator.next();
        doFnInvoker.invokeProcessElement(processContext);
      }
    } finally {
      currentElement = null;
      currentWindow = null;
    }
  }

  private void processElementForWindowObservingSizedElementAndRestriction(
      WindowedValue<KV<KV<InputT, KV<RestrictionT, WatermarkEstimatorStateT>>, Double>> elem) {
    currentElement = elem.withValue(elem.getValue().getKey().getKey());
    windowCurrentIndex = -1;
    windowStopIndex = currentElement.getWindows().size();
    currentWindows = ImmutableList.copyOf(currentElement.getWindows());
    while (true) {
      synchronized (splitLock) {
        windowCurrentIndex++;
        if (windowCurrentIndex >= windowStopIndex) {
          // Careful to reset the split state under the same synchronized block.
          windowCurrentIndex = -1;
          windowStopIndex = 0;
          currentElement = null;
          currentWindows = null;
          currentRestriction = null;
          currentWatermarkEstimatorState = null;
          currentWindow = null;
          currentTracker = null;
          currentWatermarkEstimator = null;
          initialWatermark = null;
          return;
        }
        currentRestriction = elem.getValue().getKey().getValue().getKey();
        currentWatermarkEstimatorState = elem.getValue().getKey().getValue().getValue();
        currentWindow = currentWindows.get(windowCurrentIndex);
        currentTrackerClaimed = new AtomicBoolean(false);
        currentTracker =
            RestrictionTrackers.observe(
                doFnInvoker.invokeNewTracker(processContext),
                new ClaimObserver<PositionT>() {
                  private final AtomicBoolean claimed =
                      Preconditions.checkNotNull(currentTrackerClaimed);

                  @Override
                  public void onClaimed(PositionT position) {
                    claimed.lazySet(true);
                  }

                  @Override
                  public void onClaimFailed(PositionT position) {}
                });
        currentWatermarkEstimator =
            WatermarkEstimators.threadSafe(doFnInvoker.invokeNewWatermarkEstimator(processContext));
        initialWatermark = currentWatermarkEstimator.getWatermarkAndState().getKey();
      }

      // It is important to ensure that {@code splitLock} is not held during #invokeProcessElement
      DoFn.ProcessContinuation continuation = doFnInvoker.invokeProcessElement(processContext);
      // Ensure that all the work is done if the user tells us that they don't want to
      // resume processing.
      if (!continuation.shouldResume()) {
        currentTracker.checkDone();
        continue;
      }

      // Attempt to checkpoint the current restriction.
      HandlesSplits.SplitResult splitResult =
          trySplitForElementAndRestriction(0, continuation.resumeDelay(), false);

      /**
       * After the user has chosen to resume processing later, either the restriction is already
       * done and the user unknowingly claimed the last element or the Runner may have stolen the
       * remainder of work through a split call so the above trySplit may return null. If so, the
       * current restriction must be done.
       */
      if (splitResult == null) {
        currentTracker.checkDone();
        continue;
      }
      // Forward the split to the bundle level split listener.
      splitListener.split(splitResult.getPrimaryRoots(), splitResult.getResidualRoots());
    }
  }

  /**
   * An abstract class which forwards split and progress calls allowing the implementer to choose
   * where input elements are sent.
   */
  private abstract class SplittableFnDataReceiver
      implements HandlesSplits, FnDataReceiver<WindowedValue> {
    @Override
    public HandlesSplits.SplitResult trySplit(double fractionOfRemainder) {
      return trySplitForElementAndRestriction(fractionOfRemainder, Duration.ZERO, true);
    }

    @Override
    public double getProgress() {
      Progress progress = FnApiDoFnRunner.this.getProgress();
      if (progress != null) {
        double totalWork = progress.getWorkCompleted() + progress.getWorkRemaining();
        if (totalWork > 0) {
          return progress.getWorkCompleted() / totalWork;
        }
      }
      return 0;
    }
  }

  private Progress getProgress() {
    synchronized (splitLock) {
      if (currentTracker instanceof RestrictionTracker.HasProgress && currentWindow != null) {
        return ProgressUtils.scaleProgress(
            ((HasProgress) currentTracker).getProgress(), windowCurrentIndex, windowStopIndex);
      }
    }
    return null;
  }

  private WindowedSplitResult calculateRestrictionSize(
      WindowedSplitResult splitResult, String errorContext) {
    double fullSize =
        splitResult.getResidualInUnprocessedWindowsRoot() == null
                && splitResult.getPrimaryInFullyProcessedWindowsRoot() == null
            ? 0
            : doFnInvoker.invokeGetSize(
                new DelegatingArgumentProvider<InputT, OutputT>(processContext, errorContext) {
                  @Override
                  public Object restriction() {
                    return currentRestriction;
                  }

                  @Override
                  public RestrictionTracker<?, ?> restrictionTracker() {
                    return doFnInvoker.invokeNewTracker(this);
                  }
                });
    double primarySize =
        splitResult.getPrimarySplitRoot() == null
            ? 0
            : doFnInvoker.invokeGetSize(
                new DelegatingArgumentProvider<InputT, OutputT>(processContext, errorContext) {
                  @Override
                  public Object restriction() {
                    return ((KV<?, KV<?, ?>>) splitResult.getPrimarySplitRoot().getValue())
                        .getValue()
                        .getKey();
                  }

                  @Override
                  public RestrictionTracker<?, ?> restrictionTracker() {
                    return doFnInvoker.invokeNewTracker(this);
                  }
                });
    double residualSize =
        splitResult.getResidualSplitRoot() == null
            ? 0
            : doFnInvoker.invokeGetSize(
                new DelegatingArgumentProvider<InputT, OutputT>(processContext, errorContext) {
                  @Override
                  public Object restriction() {
                    return ((KV<?, KV<?, ?>>) splitResult.getResidualSplitRoot().getValue())
                        .getValue()
                        .getKey();
                  }

                  @Override
                  public RestrictionTracker<?, ?> restrictionTracker() {
                    return doFnInvoker.invokeNewTracker(this);
                  }
                });
    return WindowedSplitResult.forRoots(
        splitResult.getPrimaryInFullyProcessedWindowsRoot() == null
            ? null
            : WindowedValues.of(
                KV.of(splitResult.getPrimaryInFullyProcessedWindowsRoot().getValue(), fullSize),
                splitResult.getPrimaryInFullyProcessedWindowsRoot().getTimestamp(),
                splitResult.getPrimaryInFullyProcessedWindowsRoot().getWindows(),
                splitResult.getPrimaryInFullyProcessedWindowsRoot().getPaneInfo()),
        splitResult.getPrimarySplitRoot() == null
            ? null
            : WindowedValues.of(
                KV.of(splitResult.getPrimarySplitRoot().getValue(), primarySize),
                splitResult.getPrimarySplitRoot().getTimestamp(),
                splitResult.getPrimarySplitRoot().getWindows(),
                splitResult.getPrimarySplitRoot().getPaneInfo()),
        splitResult.getResidualSplitRoot() == null
            ? null
            : WindowedValues.of(
                KV.of(splitResult.getResidualSplitRoot().getValue(), residualSize),
                splitResult.getResidualSplitRoot().getTimestamp(),
                splitResult.getResidualSplitRoot().getWindows(),
                splitResult.getResidualSplitRoot().getPaneInfo()),
        splitResult.getResidualInUnprocessedWindowsRoot() == null
            ? null
            : WindowedValues.of(
                KV.of(splitResult.getResidualInUnprocessedWindowsRoot().getValue(), fullSize),
                splitResult.getResidualInUnprocessedWindowsRoot().getTimestamp(),
                splitResult.getResidualInUnprocessedWindowsRoot().getWindows(),
                splitResult.getResidualInUnprocessedWindowsRoot().getPaneInfo()));
  }

  private static <WatermarkEstimatorStateT> WindowedSplitResult computeWindowSplitResult(
      WindowedValue currentElement,
      Object currentRestriction,
      BoundedWindow currentWindow,
      List<BoundedWindow> windows,
      WatermarkEstimatorStateT currentWatermarkEstimatorState,
      int toIndex,
      int fromIndex,
      int stopWindowIndex,
      SplitResult<?> splitResult,
      KV<Instant, WatermarkEstimatorStateT> watermarkAndState) {
    List<BoundedWindow> primaryFullyProcessedWindows = windows.subList(0, toIndex);
    List<BoundedWindow> residualUnprocessedWindows = windows.subList(fromIndex, stopWindowIndex);
    WindowedSplitResult windowedSplitResult;

    windowedSplitResult =
        WindowedSplitResult.forRoots(
            primaryFullyProcessedWindows.isEmpty()
                ? null
                : WindowedValues.of(
                    KV.of(
                        currentElement.getValue(),
                        KV.of(currentRestriction, currentWatermarkEstimatorState)),
                    currentElement.getTimestamp(),
                    primaryFullyProcessedWindows,
                    currentElement.getPaneInfo()),
            splitResult == null
                ? null
                : WindowedValues.of(
                    KV.of(
                        currentElement.getValue(),
                        KV.of(splitResult.getPrimary(), currentWatermarkEstimatorState)),
                    currentElement.getTimestamp(),
                    currentWindow,
                    currentElement.getPaneInfo()),
            splitResult == null
                ? null
                : WindowedValues.of(
                    KV.of(
                        currentElement.getValue(),
                        KV.of(splitResult.getResidual(), watermarkAndState.getValue())),
                    currentElement.getTimestamp(),
                    currentWindow,
                    currentElement.getPaneInfo()),
            residualUnprocessedWindows.isEmpty()
                ? null
                : WindowedValues.of(
                    KV.of(
                        currentElement.getValue(),
                        KV.of(currentRestriction, currentWatermarkEstimatorState)),
                    currentElement.getTimestamp(),
                    residualUnprocessedWindows,
                    currentElement.getPaneInfo()));
    return windowedSplitResult;
  }

  @VisibleForTesting
  static <WatermarkEstimatorStateT> SplitResultsWithStopIndex computeSplitForProcess(
      WindowedValue currentElement,
      Object currentRestriction,
      BoundedWindow currentWindow,
      List<BoundedWindow> windows,
      WatermarkEstimatorStateT currentWatermarkEstimatorState,
      double fractionOfRemainder,
      RestrictionTracker currentTracker,
      HandlesSplits splitDelegate,
      KV<Instant, WatermarkEstimatorStateT> watermarkAndState,
      int currentWindowIndex,
      int stopWindowIndex) {
    // We should only have currentTracker or splitDelegate.
    checkArgument((currentTracker != null) ^ (splitDelegate != null));
    // When we have currentTracker, the watermarkAndState should not be null.
    if (currentTracker != null) {
      checkNotNull(watermarkAndState);
    }

    WindowedSplitResult windowedSplitResult = null;
    HandlesSplits.SplitResult downstreamSplitResult = null;
    int newWindowStopIndex = stopWindowIndex;
    // If we are not on the last window, try to compute the split which is on the current window or
    // on a future window.
    if (currentWindowIndex != stopWindowIndex - 1) {
      // Compute the fraction of the remainder relative to the scaled progress.
      Progress elementProgress;
      if (currentTracker != null) {
        if (currentTracker instanceof HasProgress) {
          elementProgress = ((HasProgress) currentTracker).getProgress();
        } else {
          elementProgress = Progress.from(0, 1);
        }
      } else {
        double elementCompleted = splitDelegate.getProgress();
        elementProgress = Progress.from(elementCompleted, 1 - elementCompleted);
      }
      Progress scaledProgress =
          ProgressUtils.scaleProgress(elementProgress, currentWindowIndex, stopWindowIndex);
      double scaledFractionOfRemainder = scaledProgress.getWorkRemaining() * fractionOfRemainder;

      // The fraction is out of the current window and hence we will split at the closest window
      // boundary.
      if (scaledFractionOfRemainder >= elementProgress.getWorkRemaining()) {
        newWindowStopIndex =
            (int)
                Math.min(
                    stopWindowIndex - 1,
                    currentWindowIndex
                        + Math.max(
                            1,
                            Math.round(
                                (elementProgress.getWorkCompleted() + scaledFractionOfRemainder)
                                    / (elementProgress.getWorkCompleted()
                                        + elementProgress.getWorkRemaining()))));
        windowedSplitResult =
            computeWindowSplitResult(
                currentElement,
                currentRestriction,
                currentWindow,
                windows,
                currentWatermarkEstimatorState,
                newWindowStopIndex,
                newWindowStopIndex,
                stopWindowIndex,
                null,
                watermarkAndState);
      } else {
        // Compute the element split with the scaled fraction.
        SplitResult<?> elementSplit = null;
        if (currentTracker != null) {
          elementSplit =
              currentTracker.trySplit(
                  scaledFractionOfRemainder / elementProgress.getWorkRemaining());
        } else {
          downstreamSplitResult = splitDelegate.trySplit(scaledFractionOfRemainder);
        }
        newWindowStopIndex = currentWindowIndex + 1;
        int toIndex =
            (elementSplit == null && downstreamSplitResult == null)
                ? newWindowStopIndex
                : currentWindowIndex;
        windowedSplitResult =
            computeWindowSplitResult(
                currentElement,
                currentRestriction,
                currentWindow,
                windows,
                currentWatermarkEstimatorState,
                toIndex,
                newWindowStopIndex,
                stopWindowIndex,
                elementSplit,
                watermarkAndState);
      }
    } else {
      // We are on the last window then compute the element split with given fraction.
      SplitResult<?> elementSplitResult = null;
      newWindowStopIndex = stopWindowIndex;
      if (currentTracker != null) {
        elementSplitResult = currentTracker.trySplit(fractionOfRemainder);
      } else {
        downstreamSplitResult = splitDelegate.trySplit(fractionOfRemainder);
      }
      if (elementSplitResult == null && downstreamSplitResult == null) {
        return null;
      }
      windowedSplitResult =
          computeWindowSplitResult(
              currentElement,
              currentRestriction,
              currentWindow,
              windows,
              currentWatermarkEstimatorState,
              currentWindowIndex,
              stopWindowIndex,
              stopWindowIndex,
              elementSplitResult,
              watermarkAndState);
    }
    return SplitResultsWithStopIndex.of(
        windowedSplitResult, downstreamSplitResult, newWindowStopIndex);
  }

  @VisibleForTesting
  static <WatermarkEstimatorStateT> HandlesSplits.SplitResult constructSplitResult(
      WindowedSplitResult windowedSplitResult,
      HandlesSplits.SplitResult downstreamElementSplit,
      Coder fullInputCoder,
      Instant initialWatermark,
      KV<Instant, WatermarkEstimatorStateT> watermarkAndState,
      String pTransformId,
      String mainInputId,
      Collection<String> outputIds,
      Duration resumeDelay) {
    // The element split cannot from both windowedSplitResult and downstreamElementSplit.
    checkArgument(
        (windowedSplitResult == null || windowedSplitResult.getResidualSplitRoot() == null)
            || downstreamElementSplit == null);
    List<BundleApplication> primaryRoots = new ArrayList<>();
    List<DelayedBundleApplication> residualRoots = new ArrayList<>();

    // Encode window splits.
    if (windowedSplitResult != null
        && windowedSplitResult.getPrimaryInFullyProcessedWindowsRoot() != null) {
      ByteStringOutputStream primaryInOtherWindowsBytes = new ByteStringOutputStream();
      try {
        fullInputCoder.encode(
            windowedSplitResult.getPrimaryInFullyProcessedWindowsRoot(),
            primaryInOtherWindowsBytes);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      BundleApplication.Builder primaryApplicationInOtherWindows =
          BundleApplication.newBuilder()
              .setTransformId(pTransformId)
              .setInputId(mainInputId)
              .setElement(primaryInOtherWindowsBytes.toByteString());
      primaryRoots.add(primaryApplicationInOtherWindows.build());
    }
    if (windowedSplitResult != null
        && windowedSplitResult.getResidualInUnprocessedWindowsRoot() != null) {
      ByteStringOutputStream bytesOut = new ByteStringOutputStream();
      try {
        fullInputCoder.encode(windowedSplitResult.getResidualInUnprocessedWindowsRoot(), bytesOut);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      BundleApplication.Builder residualInUnprocessedWindowsRoot =
          BundleApplication.newBuilder()
              .setTransformId(pTransformId)
              .setInputId(mainInputId)
              .setElement(bytesOut.toByteString());
      // We don't want to change the output watermarks or set the checkpoint resume time since
      // that applies to the current window.
      Map<String, org.apache.beam.vendor.grpc.v1p69p0.com.google.protobuf.Timestamp>
          outputWatermarkMapForUnprocessedWindows = new HashMap<>();
      if (!initialWatermark.equals(GlobalWindow.TIMESTAMP_MIN_VALUE)) {
        org.apache.beam.vendor.grpc.v1p69p0.com.google.protobuf.Timestamp outputWatermark =
            org.apache.beam.vendor.grpc.v1p69p0.com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(initialWatermark.getMillis() / 1000)
                .setNanos((int) (initialWatermark.getMillis() % 1000) * 1000000)
                .build();
        for (String outputId : outputIds) {
          outputWatermarkMapForUnprocessedWindows.put(outputId, outputWatermark);
        }
      }
      residualInUnprocessedWindowsRoot.putAllOutputWatermarks(
          outputWatermarkMapForUnprocessedWindows);
      residualRoots.add(
          DelayedBundleApplication.newBuilder()
              .setApplication(residualInUnprocessedWindowsRoot)
              .build());
    }

    ByteStringOutputStream primaryBytes = new ByteStringOutputStream();
    ByteStringOutputStream residualBytes = new ByteStringOutputStream();
    // Encode element split from windowedSplitResult or from downstream element split. It's possible
    // that there is no element split.
    if (windowedSplitResult != null && windowedSplitResult.getResidualSplitRoot() != null) {
      // When there is element split in windowedSplitResult, the resumeDelay should not be null.
      checkNotNull(resumeDelay);
      try {
        fullInputCoder.encode(windowedSplitResult.getPrimarySplitRoot(), primaryBytes);
        fullInputCoder.encode(windowedSplitResult.getResidualSplitRoot(), residualBytes);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      primaryRoots.add(
          BundleApplication.newBuilder()
              .setTransformId(pTransformId)
              .setInputId(mainInputId)
              .setElement(primaryBytes.toByteString())
              .build());
      BundleApplication.Builder residualApplication =
          BundleApplication.newBuilder()
              .setTransformId(pTransformId)
              .setInputId(mainInputId)
              .setElement(residualBytes.toByteString());
      Map<String, org.apache.beam.vendor.grpc.v1p69p0.com.google.protobuf.Timestamp>
          outputWatermarkMap = new HashMap<>();
      if (!watermarkAndState.getKey().equals(GlobalWindow.TIMESTAMP_MIN_VALUE)) {
        org.apache.beam.vendor.grpc.v1p69p0.com.google.protobuf.Timestamp outputWatermark =
            org.apache.beam.vendor.grpc.v1p69p0.com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(watermarkAndState.getKey().getMillis() / 1000)
                .setNanos((int) (watermarkAndState.getKey().getMillis() % 1000) * 1000000)
                .build();
        for (String outputId : outputIds) {
          outputWatermarkMap.put(outputId, outputWatermark);
        }
      }
      residualApplication.putAllOutputWatermarks(outputWatermarkMap);
      residualRoots.add(
          DelayedBundleApplication.newBuilder()
              .setApplication(residualApplication)
              .setRequestedTimeDelay(Durations.fromMillis(resumeDelay.getMillis()))
              .build());

    } else if (downstreamElementSplit != null) {
      primaryRoots.add(Iterables.getOnlyElement(downstreamElementSplit.getPrimaryRoots()));
      residualRoots.add(Iterables.getOnlyElement(downstreamElementSplit.getResidualRoots()));
    }

    return HandlesSplits.SplitResult.of(primaryRoots, residualRoots);
  }

  private HandlesSplits.SplitResult trySplitForElementAndRestriction(
      double fractionOfRemainder, Duration resumeDelay, boolean requireClaimForCheckpoint) {
    KV<Instant, WatermarkEstimatorStateT> watermarkAndState;
    WindowedSplitResult windowedSplitResult = null;
    synchronized (splitLock) {
      // There is nothing to split if we are between element and restriction processing calls.
      if (currentTracker == null) {
        return null;
      }
      // The tracker has not yet been claimed meaning that a checkpoint won't meaningfully advance.
      if (fractionOfRemainder == 0
          && requireClaimForCheckpoint
          && currentTrackerClaimed != null
          && !currentTrackerClaimed.get()) {
        return null;
      }
      // Make sure to get the output watermark before we split to ensure that the lower bound
      // applies to the residual.
      watermarkAndState = currentWatermarkEstimator.getWatermarkAndState();
      SplitResultsWithStopIndex splitResult =
          computeSplitForProcess(
              currentElement,
              currentRestriction,
              currentWindow,
              currentWindows,
              currentWatermarkEstimatorState,
              fractionOfRemainder,
              currentTracker,
              null,
              watermarkAndState,
              windowCurrentIndex,
              windowStopIndex);
      if (splitResult == null) {
        return null;
      }
      windowStopIndex = splitResult.getNewWindowStopIndex();
      // Populate the size of primary/residual.
      windowedSplitResult =
          calculateRestrictionSize(
              splitResult.getWindowSplit(),
              PTransformTranslation.SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN
                  + "/GetSize");
    }
    Coder fullInputCoder = WindowedValues.getFullCoder(inputCoder, windowCoder);
    return constructSplitResult(
        windowedSplitResult,
        null,
        fullInputCoder,
        initialWatermark,
        watermarkAndState,
        pTransformId,
        mainInputId,
        pTransform.getOutputsMap().keySet(),
        resumeDelay);
  }

  private <K> void processTimer(
      String timerIdOrTimerFamilyId, TimeDomain timeDomain, Timer<K> timer) {
    checkNotNull(timerBundleTracker);
    try {
      currentKey = timer.getUserKey();
      Iterator<BoundedWindow> windowIterator =
          (Iterator<BoundedWindow>) timer.getWindows().iterator();
      while (windowIterator.hasNext()) {
        currentWindow = windowIterator.next();
        Modifications bundleModifications = timerBundleTracker.getBundleModifications();
        Table<String, String, Timer<K>> modifiedTimerIds =
            bundleModifications.getModifiedTimerIds();
        NavigableSet<TimerInfo<K>> earlierTimers =
            bundleModifications
                .getModifiedTimersOrdered(timeDomain)
                .headSet(TimerInfo.of(timer, "", timeDomain), true);
        while (!earlierTimers.isEmpty()) {
          TimerInfo<K> insertedTimer = earlierTimers.pollFirst();
          if (timerModified(
              modifiedTimerIds, insertedTimer.getTimerFamilyOrId(), insertedTimer.getTimer())) {
            continue;
          }

          String timerId =
              insertedTimer.getTimer().getDynamicTimerTag().isEmpty()
                  ? insertedTimer.getTimerFamilyOrId()
                  : insertedTimer.getTimer().getDynamicTimerTag();
          String timerFamily =
              insertedTimer.getTimer().getDynamicTimerTag().isEmpty()
                  ? ""
                  : insertedTimer.getTimerFamilyOrId();

          // If this timer was created previously in the bundle as an overwrite of a previous timer,
          // we must make sure
          // to clear the old timer. Since we are firing the timer inline, the runner doesn't know
          // that the old timer
          // was overwritten, and will otherwise fire it - causing a spurious timer fire.
          modifiedTimerIds.put(
              insertedTimer.getTimerFamilyOrId(),
              insertedTimer.getTimer().getDynamicTimerTag(),
              Timer.cleared(
                  insertedTimer.getTimer().getUserKey(),
                  insertedTimer.getTimer().getDynamicTimerTag(),
                  insertedTimer.getTimer().getWindows()));
          // It's important to call processTimer after inserting the above deletion, otherwise the
          // above line
          // would overwrite any looping timer with a deletion.
          processTimerDirect(
              timerFamily, timerId, insertedTimer.getTimeDomain(), insertedTimer.getTimer());
        }

        if (!timerModified(modifiedTimerIds, timerIdOrTimerFamilyId, timer)) {
          // The timerIdOrTimerFamilyId contains either a timerId from timer declaration or
          // timerFamilyId
          // from timer family declaration.
          boolean isFamily = timerIdOrTimerFamilyId.startsWith(TimerFamilyDeclaration.PREFIX);
          String timerId = isFamily ? "" : timerIdOrTimerFamilyId;
          String timerFamilyId = isFamily ? timerIdOrTimerFamilyId : "";
          processTimerDirect(timerFamilyId, timerId, timeDomain, timer);
        }
      }
    } finally {
      currentKey = null;
      currentTimer = null;
      currentTimeDomain = null;
      currentWindow = null;
    }
  }

  private <K> boolean timerModified(
      Table<String, String, Timer<K>> modifiedTimerIds, String timerFamilyOrId, Timer<K> timer) {
    @Nullable
    Timer<K> modifiedTimer = modifiedTimerIds.get(timerFamilyOrId, timer.getDynamicTimerTag());
    return modifiedTimer != null && !modifiedTimer.equals(timer);
  }

  private <K> void processTimerDirect(
      String timerFamilyId, String timerId, TimeDomain timeDomain, Timer<K> timer) {
    currentTimer = timer;
    currentTimeDomain = timeDomain;
    doFnInvoker.invokeOnTimer(timerId, timerFamilyId, onTimerContext);
  }

  private <K> void processOnWindowExpiration(Timer<K> timer) {
    try {
      currentKey = timer.getUserKey();
      currentTimer = timer;
      Iterator<BoundedWindow> windowIterator =
          (Iterator<BoundedWindow>) timer.getWindows().iterator();
      while (windowIterator.hasNext()) {
        currentWindow = windowIterator.next();
        doFnInvoker.invokeOnWindowExpiration(onWindowExpirationContext);
      }
    } finally {
      currentKey = null;
      currentTimer = null;
      currentWindow = null;
    }
  }

  private void finishBundle() throws Exception {
    if (timerBundleTracker != null) {
      timerBundleTracker.outputTimers(outboundTimerReceivers::get);
    }

    doFnInvoker.invokeFinishBundle(finishBundleArgumentProvider);

    this.stateAccessor.finalizeState();
  }

  private void tearDown() {
    doFnInvoker.invokeTeardown();
  }

  /** Outputs the given element to the specified set of consumers wrapping any exceptions. */
  private <T> void outputTo(FnDataReceiver<WindowedValue<T>> consumer, WindowedValue<T> output) {
    if (currentWatermarkEstimator instanceof TimestampObservingWatermarkEstimator) {
      ((TimestampObservingWatermarkEstimator) currentWatermarkEstimator)
          .observeTimestamp(output.getTimestamp());
    }
    try {
      consumer.accept(output);
    } catch (Throwable t) {
      throw UserCodeException.wrap(t);
    }
  }

  private class FnApiTimer<K> implements org.apache.beam.sdk.state.Timer {
    private final String timerIdOrFamily;
    private final K userKey;
    private final String dynamicTimerTag;
    private final TimeDomain timeDomain;
    private final Instant fireTimestamp;
    private final Instant elementTimestampOrTimerHoldTimestamp;
    private final BoundedWindow boundedWindow;
    private final PaneInfo paneInfo;

    private @Nullable Instant outputTimestamp;
    private boolean noOutputTimestamp;
    private Duration period = Duration.ZERO;
    private Duration offset = Duration.ZERO;

    FnApiTimer(
        String timerIdOrFamily,
        K userKey,
        String dynamicTimerTag,
        BoundedWindow boundedWindow,
        Instant elementTimestampOrTimerHoldTimestamp,
        Instant elementTimestampOrTimerFireTimestamp,
        PaneInfo paneInfo,
        TimeDomain timeDomain) {
      this.timerIdOrFamily = timerIdOrFamily;
      this.userKey = userKey;
      this.dynamicTimerTag = dynamicTimerTag;
      this.elementTimestampOrTimerHoldTimestamp = elementTimestampOrTimerHoldTimestamp;
      this.boundedWindow = boundedWindow;
      this.paneInfo = paneInfo;
      this.noOutputTimestamp = false;
      this.timeDomain = timeDomain;

      switch (timeDomain) {
        case EVENT_TIME:
          fireTimestamp = elementTimestampOrTimerFireTimestamp;
          break;
        case PROCESSING_TIME:
          // TODO: This should use an injected clock when using TestStream.
          fireTimestamp = new Instant(DateTimeUtils.currentTimeMillis());
          break;
        default:
          throw new IllegalArgumentException(
              String.format("Unknown or unsupported time domain %s", timeDomain));
      }
    }

    @Override
    public void set(Instant absoluteTime) {
      checkNotNull(timerBundleTracker);
      // Ensures that the target time is reasonable. For event time timers this means that the time
      // should be prior to window GC time.
      if (TimeDomain.EVENT_TIME.equals(timeDomain)) {
        Instant windowExpiry = LateDataUtils.garbageCollectionTime(currentWindow, allowedLateness);
        checkArgument(
            !absoluteTime.isAfter(windowExpiry),
            "Attempted to set event time timer for %s but that is after"
                + " the expiration of window %s",
            absoluteTime,
            windowExpiry);
      }
      timerBundleTracker.timerModified(timerIdOrFamily, timeDomain, getTimerForTime(absoluteTime));
    }

    @Override
    public void setRelative() {
      checkNotNull(timerBundleTracker);
      Instant target;
      if (period.equals(Duration.ZERO)) {
        target = fireTimestamp.plus(offset);
      } else {
        long millisSinceStart = fireTimestamp.plus(offset).getMillis() % period.getMillis();
        target =
            millisSinceStart == 0
                ? fireTimestamp
                : fireTimestamp.plus(period).minus(Duration.millis(millisSinceStart));
      }
      target = minTargetAndGcTime(target);
      timerBundleTracker.timerModified(timerIdOrFamily, timeDomain, getTimerForTime(target));
    }

    @Override
    public void clear() {
      checkNotNull(timerBundleTracker);
      timerBundleTracker.timerModified(timerIdOrFamily, timeDomain, getClearedTimer());
    }

    @Override
    public org.apache.beam.sdk.state.Timer offset(Duration offset) {
      this.offset = offset;
      return this;
    }

    @Override
    public org.apache.beam.sdk.state.Timer align(Duration period) {
      this.period = period;
      return this;
    }

    @Override
    public org.apache.beam.sdk.state.Timer withOutputTimestamp(Instant outputTime) {
      this.outputTimestamp = outputTime;
      this.noOutputTimestamp = false;
      return this;
    }

    @Override
    public org.apache.beam.sdk.state.Timer withNoOutputTimestamp() {
      this.outputTimestamp = null;
      this.noOutputTimestamp = true;
      return this;
    }

    @Override
    public Instant getCurrentRelativeTime() {
      return fireTimestamp;
    }

    /**
     * For event time timers the target time should be prior to window GC time. So it returns
     * min(time to set, GC Time of window).
     */
    private Instant minTargetAndGcTime(Instant target) {
      if (TimeDomain.EVENT_TIME.equals(timeDomain)) {
        Instant windowExpiry = LateDataUtils.garbageCollectionTime(currentWindow, allowedLateness);
        if (target.isAfter(windowExpiry)) {
          return windowExpiry;
        }
      }
      return target;
    }

    private Timer<K> getClearedTimer() {
      return Timer.cleared(userKey, dynamicTimerTag, Collections.singletonList(boundedWindow));
    }

    @SuppressWarnings("deprecation") // Allowed Skew is deprecated for users, but must be respected
    private Timer<K> getTimerForTime(Instant scheduledTime) {
      if (outputTimestamp != null) {
        Instant lowerBound;
        try {
          lowerBound = elementTimestampOrTimerHoldTimestamp.minus(doFn.getAllowedTimestampSkew());
        } catch (ArithmeticException e) {
          lowerBound = BoundedWindow.TIMESTAMP_MIN_VALUE;
        }
        if (outputTimestamp.isBefore(lowerBound)
            || outputTimestamp.isAfter(BoundedWindow.TIMESTAMP_MAX_VALUE)) {
          throw new IllegalArgumentException(
              String.format(
                  "Cannot output timer with output timestamp %s. Output timestamps must be no "
                      + "earlier than the timestamp of the current input (%s) minus the allowed skew "
                      + "(%s) and no later than %s. See the DoFn#getAllowedTimestampSkew() Javadoc for "
                      + "details on changing the allowed skew.",
                  outputTimestamp,
                  elementTimestampOrTimerHoldTimestamp,
                  doFn.getAllowedTimestampSkew().getMillis() >= Integer.MAX_VALUE
                      ? doFn.getAllowedTimestampSkew()
                      : PeriodFormat.getDefault().print(doFn.getAllowedTimestampSkew().toPeriod()),
                  BoundedWindow.TIMESTAMP_MAX_VALUE));
        }
      }

      // Output timestamp is set to the delivery time if not initialized by an user.
      if (!noOutputTimestamp
          && outputTimestamp == null
          && TimeDomain.EVENT_TIME.equals(timeDomain)) {
        outputTimestamp = scheduledTime;
      }

      // For processing timers
      if (!noOutputTimestamp && outputTimestamp == null) {
        // For processing timers output timestamp will be:
        // 1) timestamp of input element
        // OR
        // 2) hold timestamp of firing timer.
        outputTimestamp = elementTimestampOrTimerHoldTimestamp;
      }
      if (outputTimestamp != null) {
        Instant windowExpiry = LateDataUtils.garbageCollectionTime(currentWindow, allowedLateness);
        if (TimeDomain.EVENT_TIME.equals(timeDomain)) {
          checkArgument(
              !outputTimestamp.isAfter(scheduledTime),
              "Attempted to set an event-time timer with an output timestamp of %s that is"
                  + " after the timer firing timestamp %s",
              outputTimestamp,
              scheduledTime);
          checkArgument(
              !scheduledTime.isAfter(windowExpiry),
              "Attempted to set an event-time timer with a firing timestamp of %s that is"
                  + " after the expiration of window %s",
              scheduledTime,
              windowExpiry);
        } else {
          checkArgument(
              !outputTimestamp.isAfter(windowExpiry),
              "Attempted to set a processing-time timer with an output timestamp of %s that is"
                  + " after the expiration of window %s",
              outputTimestamp,
              windowExpiry);
        }
      } else {
        outputTimestamp = BoundedWindow.TIMESTAMP_MAX_VALUE.plus(Duration.millis(1));
      }
      return Timer.of(
          userKey,
          dynamicTimerTag,
          Collections.singletonList(boundedWindow),
          scheduledTime,
          outputTimestamp,
          paneInfo);
    }
  }

  private class FnApiTimerMap<K> implements TimerMap {
    private final String timerFamilyId;
    private final K userKey;
    private final TimeDomain timeDomain;
    private final Instant elementTimestampOrTimerHoldTimestamp;
    private final Instant elementTimestampOrTimerFireTimestamp;
    private final BoundedWindow boundedWindow;
    private final PaneInfo paneInfo;

    FnApiTimerMap(
        String timerFamilyId,
        K userKey,
        BoundedWindow boundedWindow,
        Instant elementTimestampOrTimerHoldTimestamp,
        Instant elementTimestampOrTimerFireTimestamp,
        PaneInfo paneInfo) {
      this.timerFamilyId = timerFamilyId;
      this.userKey = userKey;
      this.elementTimestampOrTimerHoldTimestamp = elementTimestampOrTimerHoldTimestamp;
      this.elementTimestampOrTimerFireTimestamp = elementTimestampOrTimerFireTimestamp;
      this.boundedWindow = boundedWindow;
      this.paneInfo = paneInfo;
      this.timeDomain =
          translateTimeDomain(
              parDoPayload.getTimerFamilySpecsMap().get(timerFamilyId).getTimeDomain());
    }

    @Override
    public void set(String dynamicTimerTag, Instant absoluteTime) {
      get(dynamicTimerTag).set(absoluteTime);
    }

    @Override
    public org.apache.beam.sdk.state.Timer get(String dynamicTimerTag) {
      return new FnApiTimer(
          timerFamilyId,
          userKey,
          dynamicTimerTag,
          boundedWindow,
          elementTimestampOrTimerHoldTimestamp,
          elementTimestampOrTimerFireTimestamp,
          paneInfo,
          timeDomain);
    }
  }

  @SuppressWarnings("deprecation") // Allowed Skew is deprecated for users, but must be respected
  private void checkTimestamp(Instant timestamp) {
    Instant lowerBound;
    try {
      lowerBound = currentElement.getTimestamp().minus(doFn.getAllowedTimestampSkew());
    } catch (ArithmeticException e) {
      lowerBound = BoundedWindow.TIMESTAMP_MIN_VALUE;
    }

    if (timestamp.isBefore(lowerBound) || timestamp.isAfter(BoundedWindow.TIMESTAMP_MAX_VALUE)) {
      throw new IllegalArgumentException(
          String.format(
              "Cannot output with timestamp %s. Output timestamps must be no earlier than the "
                  + "timestamp of the current input (%s) minus the allowed skew (%s) and no later "
                  + "than %s. See the DoFn#getAllowedTimestampSkew() Javadoc for details on "
                  + "changing the allowed skew.",
              timestamp,
              currentElement.getTimestamp(),
              doFn.getAllowedTimestampSkew().getMillis() >= Integer.MAX_VALUE
                  ? doFn.getAllowedTimestampSkew()
                  : PeriodFormat.getDefault().print(doFn.getAllowedTimestampSkew().toPeriod()),
              BoundedWindow.TIMESTAMP_MAX_VALUE));
    }
  }

  private class StartBundleArgumentProvider extends BaseArgumentProvider<InputT, OutputT> {
    private class Context extends DoFn<InputT, OutputT>.StartBundleContext {
      Context() {
        doFn.super();
      }

      @Override
      public PipelineOptions getPipelineOptions() {
        return pipelineOptions;
      }
    }

    private final StartBundleArgumentProvider.Context context =
        new StartBundleArgumentProvider.Context();

    @Override
    public DoFn<InputT, OutputT>.StartBundleContext startBundleContext(DoFn<InputT, OutputT> doFn) {
      return context;
    }

    @Override
    public PipelineOptions pipelineOptions() {
      return pipelineOptions;
    }

    @Override
    public BundleFinalizer bundleFinalizer() {
      return bundleFinalizer;
    }

    @Override
    public String getErrorContext() {
      return "FnApiDoFnRunner/StartBundle";
    }
  }

  private class FinishBundleArgumentProvider extends BaseArgumentProvider<InputT, OutputT> {
    private class Context extends DoFn<InputT, OutputT>.FinishBundleContext {
      Context() {
        doFn.super();
      }

      @Override
      public PipelineOptions getPipelineOptions() {
        return pipelineOptions;
      }

      @Override
      public void output(OutputT output, Instant timestamp, BoundedWindow window) {
        outputTo(
            mainOutputConsumer, WindowedValues.of(output, timestamp, window, PaneInfo.NO_FIRING));
      }

      @Override
      public <T> void output(TupleTag<T> tag, T output, Instant timestamp, BoundedWindow window) {
        FnDataReceiver<WindowedValue<T>> consumer =
            (FnDataReceiver) localNameToConsumer.get(tag.getId());
        if (consumer == null) {
          throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
        }
        outputTo(consumer, WindowedValues.of(output, timestamp, window, PaneInfo.NO_FIRING));
      }
    }

    private final FinishBundleArgumentProvider.Context context =
        new FinishBundleArgumentProvider.Context();

    @Override
    public DoFn<InputT, OutputT>.FinishBundleContext finishBundleContext(
        DoFn<InputT, OutputT> doFn) {
      return context;
    }

    @Override
    public PipelineOptions pipelineOptions() {
      return pipelineOptions;
    }

    @Override
    public BundleFinalizer bundleFinalizer() {
      return bundleFinalizer;
    }

    @Override
    public String getErrorContext() {
      return "FnApiDoFnRunner/FinishBundle";
    }
  }

  /** Provides arguments for a {@link DoFnInvoker} for a window observing method. */
  private abstract class WindowObservingProcessBundleContextBase extends ProcessBundleContextBase {
    @Override
    public BoundedWindow window() {
      return currentWindow;
    }

    @Override
    public Object sideInput(String tagId) {
      return sideInput(sideInputMapping.get(tagId));
    }

    @Override
    public <T> T sideInput(PCollectionView<T> view) {
      return stateAccessor.get(view, currentWindow);
    }
  }

  private class WindowObservingProcessBundleContext
      extends WindowObservingProcessBundleContextBase {

    @Override
    public void output(OutputT output) {
      // Don't need to check timestamp since we can always output using the input timestamp.
      outputTo(
          mainOutputConsumer,
          WindowedValues.of(
              output, currentElement.getTimestamp(), currentWindow, currentElement.getPaneInfo()));
    }

    @Override
    public <T> void output(TupleTag<T> tag, T output) {
      FnDataReceiver<WindowedValue<T>> consumer =
          (FnDataReceiver) localNameToConsumer.get(tag.getId());
      if (consumer == null) {
        throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
      }
      // Don't need to check timestamp since we can always output using the input timestamp.
      outputTo(
          consumer,
          WindowedValues.of(
              output, currentElement.getTimestamp(), currentWindow, currentElement.getPaneInfo()));
    }

    @Override
    public void outputWithTimestamp(OutputT output, Instant timestamp) {
      // TODO(https://github.com/apache/beam/issues/29637): Check that timestamp is valid once all
      // runners can provide proper timestamps.
      outputTo(
          mainOutputConsumer,
          WindowedValues.of(output, timestamp, currentWindow, currentElement.getPaneInfo()));
    }

    @Override
    public void outputWindowedValue(
        OutputT output,
        Instant timestamp,
        Collection<? extends BoundedWindow> windows,
        PaneInfo paneInfo) {
      // TODO(https://github.com/apache/beam/issues/29637): Check that timestamp is valid once all
      // runners can provide proper timestamps.
      outputTo(mainOutputConsumer, WindowedValues.of(output, timestamp, windows, paneInfo));
    }

    @Override
    public <T> void outputWithTimestamp(TupleTag<T> tag, T output, Instant timestamp) {
      // TODO(https://github.com/apache/beam/issues/29637): Check that timestamp is valid once all
      // runners can provide proper timestamps.
      FnDataReceiver<WindowedValue<T>> consumer =
          (FnDataReceiver) localNameToConsumer.get(tag.getId());
      if (consumer == null) {
        throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
      }
      outputTo(
          consumer,
          WindowedValues.of(output, timestamp, currentWindow, currentElement.getPaneInfo()));
    }

    @Override
    public <T> void outputWindowedValue(
        TupleTag<T> tag,
        T output,
        Instant timestamp,
        Collection<? extends BoundedWindow> windows,
        PaneInfo paneInfo) {
      // TODO(https://github.com/apache/beam/issues/29637): Check that timestamp is valid once all
      // runners can provide proper timestamps.
      FnDataReceiver<WindowedValue<T>> consumer =
          (FnDataReceiver) localNameToConsumer.get(tag.getId());
      if (consumer == null) {
        throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
      }
      outputTo(consumer, WindowedValues.of(output, timestamp, windows, paneInfo));
    }

    @Override
    public State state(String stateId, boolean alwaysFetched) {
      StateDeclaration stateDeclaration = doFnSignature.stateDeclarations().get(stateId);
      checkNotNull(stateDeclaration, "No state declaration found for %s", stateId);
      StateSpec<?> spec;
      try {
        spec = (StateSpec<?>) stateDeclaration.field().get(doFn);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
      State state = spec.bind(stateId, stateAccessor);
      if (alwaysFetched) {
        return (State) ((ReadableState) state).readLater();
      } else {
        return state;
      }
    }

    @Override
    public org.apache.beam.sdk.state.Timer timer(String timerId) {
      checkState(
          currentElement.getValue() instanceof KV,
          "Accessing timer in unkeyed context. Current element is not a KV: %s.",
          currentElement.getValue());

      // For the initial timestamps we pass in the current elements timestamp for the hold timestamp
      // and the current element's timestamp which will be used for the fire timestamp if this
      // timer is in the EVENT time domain.
      TimeDomain timeDomain =
          translateTimeDomain(parDoPayload.getTimerFamilySpecsMap().get(timerId).getTimeDomain());
      return new FnApiTimer(
          timerId,
          ((KV) currentElement.getValue()).getKey(),
          "",
          currentWindow,
          currentElement.getTimestamp(),
          currentElement.getTimestamp(),
          currentElement.getPaneInfo(),
          timeDomain);
    }

    @Override
    public TimerMap timerFamily(String timerFamilyId) {
      return new FnApiTimerMap(
          timerFamilyId,
          ((KV) currentElement.getValue()).getKey(),
          currentWindow,
          currentElement.getTimestamp(),
          currentElement.getTimestamp(),
          currentElement.getPaneInfo());
    }
  }

  /** Provides arguments for a {@link DoFnInvoker} for a non-window observing method. */
  private class NonWindowObservingProcessBundleContext
      extends NonWindowObservingProcessBundleContextBase {

    @Override
    public void output(OutputT output) {
      // Don't need to check timestamp since we can always output using the input timestamp.
      if (currentElement == null) {
        throw new IllegalStateException(
            "Attempting to emit an element outside of a @ProcessElement context.");
      }
      outputTo(mainOutputConsumer, currentElement.withValue(output));
    }

    @Override
    public <T> void output(TupleTag<T> tag, T output) {
      FnDataReceiver<WindowedValue<T>> consumer =
          (FnDataReceiver) localNameToConsumer.get(tag.getId());
      if (consumer == null) {
        throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
      }
      // Don't need to check timestamp since we can always output using the input timestamp.
      outputTo(consumer, currentElement.withValue(output));
    }

    @Override
    public void outputWithTimestamp(OutputT output, Instant timestamp) {
      checkTimestamp(timestamp);
      outputTo(
          mainOutputConsumer,
          WindowedValues.of(
              output, timestamp, currentElement.getWindows(), currentElement.getPaneInfo()));
    }

    @Override
    public void outputWindowedValue(
        OutputT output,
        Instant timestamp,
        Collection<? extends BoundedWindow> windows,
        PaneInfo paneInfo) {
      checkTimestamp(timestamp);
      outputTo(mainOutputConsumer, WindowedValues.of(output, timestamp, windows, paneInfo));
    }

    @Override
    public <T> void outputWithTimestamp(TupleTag<T> tag, T output, Instant timestamp) {
      checkTimestamp(timestamp);
      FnDataReceiver<WindowedValue<T>> consumer =
          (FnDataReceiver) localNameToConsumer.get(tag.getId());
      if (consumer == null) {
        throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
      }
      outputTo(
          consumer,
          WindowedValues.of(
              output, timestamp, currentElement.getWindows(), currentElement.getPaneInfo()));
    }

    @Override
    public <T> void outputWindowedValue(
        TupleTag<T> tag,
        T output,
        Instant timestamp,
        Collection<? extends BoundedWindow> windows,
        PaneInfo paneInfo) {
      checkTimestamp(timestamp);
      FnDataReceiver<WindowedValue<T>> consumer =
          (FnDataReceiver) localNameToConsumer.get(tag.getId());
      if (consumer == null) {
        throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
      }
      outputTo(consumer, WindowedValues.of(output, timestamp, windows, paneInfo));
    }
  }

  /** Provides base arguments for a {@link DoFnInvoker} for a non-window observing method. */
  private abstract class NonWindowObservingProcessBundleContextBase
      extends ProcessBundleContextBase {
    @Override
    public BoundedWindow window() {
      throw new UnsupportedOperationException(
          "Cannot access window in non-window observing context.");
    }

    @Override
    public Object sideInput(String tagId) {
      throw new UnsupportedOperationException(
          "Cannot access sideInput in non-window observing context.");
    }

    @Override
    public <T> T sideInput(PCollectionView<T> view) {
      throw new UnsupportedOperationException(
          "Cannot access sideInput in non-window observing context.");
    }

    @Override
    public State state(String stateId, boolean alwaysFetched) {
      throw new UnsupportedOperationException(
          "Cannot access state in non-window observing context.");
    }

    @Override
    public org.apache.beam.sdk.state.Timer timer(String timerId) {
      throw new UnsupportedOperationException(
          "Cannot access timer in non-window observing context.");
    }

    @Override
    public TimerMap timerFamily(String timerFamilyId) {
      throw new UnsupportedOperationException(
          "Cannot access timerFamily in non-window observing context.");
    }
  }

  /** Base implementation that does not override methods which need to be window aware. */
  private abstract class ProcessBundleContextBase extends DoFn<InputT, OutputT>.ProcessContext
      implements DoFnInvoker.ArgumentProvider<InputT, OutputT>, OutputReceiver<OutputT> {

    private ProcessBundleContextBase() {
      doFn.super();
    }

    @Override
    public PaneInfo paneInfo(DoFn<InputT, OutputT> doFn) {
      return pane();
    }

    @Override
    public DoFn<InputT, OutputT>.StartBundleContext startBundleContext(DoFn<InputT, OutputT> doFn) {
      throw new UnsupportedOperationException(
          "Cannot access StartBundleContext outside of @StartBundle method.");
    }

    @Override
    public DoFn<InputT, OutputT>.FinishBundleContext finishBundleContext(
        DoFn<InputT, OutputT> doFn) {
      throw new UnsupportedOperationException(
          "Cannot access FinishBundleContext outside of @FinishBundle method.");
    }

    @Override
    public DoFn<InputT, OutputT>.ProcessContext processContext(DoFn<InputT, OutputT> doFn) {
      return this;
    }

    @Override
    public InputT element(DoFn<InputT, OutputT> doFn) {
      return element();
    }

    @Override
    public Object key() {
      throw new UnsupportedOperationException(
          "Cannot access key as parameter outside of @OnTimer method.");
    }

    @Override
    public Object schemaElement(int index) {
      SerializableFunction converter = doFnSchemaInformation.getElementConverters().get(index);
      return converter.apply(element());
    }

    @Override
    public Instant timestamp(DoFn<InputT, OutputT> doFn) {
      return timestamp();
    }

    @Override
    public String timerId(DoFn<InputT, OutputT> doFn) {
      throw new UnsupportedOperationException(
          "Cannot access timerId as parameter outside of @OnTimer method.");
    }

    @Override
    public TimeDomain timeDomain(DoFn<InputT, OutputT> doFn) {
      throw new UnsupportedOperationException(
          "Cannot access time domain outside of @ProcessTimer method.");
    }

    @Override
    public OutputReceiver<OutputT> outputReceiver(DoFn<InputT, OutputT> doFn) {
      return this;
    }

    private final OutputReceiver<Row> mainRowOutputReceiver =
        mainOutputSchemaCoder == null
            ? null
            : new OutputReceiver<Row>() {
              private final SerializableFunction<Row, OutputT> fromRowFunction =
                  mainOutputSchemaCoder.getFromRowFunction();

              @Override
              public void output(Row output) {
                ProcessBundleContextBase.this.output(fromRowFunction.apply(output));
              }

              @Override
              public void outputWithTimestamp(Row output, Instant timestamp) {
                ProcessBundleContextBase.this.outputWithTimestamp(
                    fromRowFunction.apply(output), timestamp);
              }

              @Override
              public void outputWindowedValue(
                  Row output,
                  Instant timestamp,
                  Collection<? extends BoundedWindow> windows,
                  PaneInfo paneInfo) {
                ProcessBundleContextBase.this.outputWindowedValue(
                    fromRowFunction.apply(output), timestamp, windows, paneInfo);
              }
            };

    @Override
    public OutputReceiver<Row> outputRowReceiver(DoFn<InputT, OutputT> doFn) {
      checkState(
          mainOutputSchemaCoder != null,
          "Output with tag "
              + mainOutputTag
              + " must have a schema in order to call getRowReceiver");
      return mainRowOutputReceiver;
    }

    /** A {@link MultiOutputReceiver} which caches created instances to re-use across bundles. */
    private final MultiOutputReceiver taggedOutputReceiver =
        new MultiOutputReceiver() {
          private final Map<TupleTag<?>, OutputReceiver<?>> taggedOutputReceivers = new HashMap<>();
          private final Map<TupleTag<?>, OutputReceiver<Row>> taggedRowReceivers = new HashMap<>();

          private <T> OutputReceiver<T> createTaggedOutputReceiver(TupleTag<T> tag) {
            // Note that it is important that we use the non-tag versions here when using the main
            // output tag for performance reasons and we also rely on it for the splittable DoFn
            // context objects as well.
            if (tag == null || mainOutputTag.equals(tag)) {
              return (OutputReceiver<T>) ProcessBundleContextBase.this;
            }
            return new OutputReceiver<T>() {
              @Override
              public void output(T output) {
                ProcessBundleContextBase.this.output(tag, output);
              }

              @Override
              public void outputWithTimestamp(T output, Instant timestamp) {
                ProcessBundleContextBase.this.outputWithTimestamp(tag, output, timestamp);
              }

              @Override
              public void outputWindowedValue(
                  T output,
                  Instant timestamp,
                  Collection<? extends BoundedWindow> windows,
                  PaneInfo paneInfo) {
                ProcessBundleContextBase.this.outputWindowedValue(
                    tag, output, timestamp, windows, paneInfo);
              }
            };
          }

          private <T> OutputReceiver<Row> createTaggedRowReceiver(TupleTag<T> tag) {
            // Note that it is important that we use the non-tag versions here when using the main
            // output tag for performance reasons and we also rely on it for the splittable DoFn
            // context objects as well.
            if (tag == null || mainOutputTag.equals(tag)) {
              checkState(
                  mainOutputSchemaCoder != null,
                  "Output with tag "
                      + mainOutputTag
                      + " must have a schema in order to call getRowReceiver");
              return mainRowOutputReceiver;
            }

            Coder<T> outputCoder = (Coder<T>) outputCoders.get(tag);
            checkState(outputCoder != null, "No output tag for " + tag);
            checkState(
                outputCoder instanceof SchemaCoder,
                "Output with tag " + tag + " must have a schema in order to call getRowReceiver");
            return new OutputReceiver<Row>() {
              private SerializableFunction<Row, T> fromRowFunction =
                  ((SchemaCoder) outputCoder).getFromRowFunction();

              @Override
              public void output(Row output) {
                ProcessBundleContextBase.this.output(tag, fromRowFunction.apply(output));
              }

              @Override
              public void outputWithTimestamp(Row output, Instant timestamp) {
                ProcessBundleContextBase.this.outputWithTimestamp(
                    tag, fromRowFunction.apply(output), timestamp);
              }

              @Override
              public void outputWindowedValue(
                  Row output,
                  Instant timestamp,
                  Collection<? extends BoundedWindow> windows,
                  PaneInfo paneInfo) {
                ProcessBundleContextBase.this.outputWindowedValue(
                    tag, fromRowFunction.apply(output), timestamp, windows, paneInfo);
              }
            };
          }

          @Override
          public <T> OutputReceiver<T> get(TupleTag<T> tag) {
            return (OutputReceiver<T>)
                taggedOutputReceivers.computeIfAbsent(tag, this::createTaggedOutputReceiver);
          }

          @Override
          public <T> OutputReceiver<Row> getRowReceiver(TupleTag<T> tag) {
            return taggedRowReceivers.computeIfAbsent(tag, this::createTaggedRowReceiver);
          }
        };

    @Override
    public MultiOutputReceiver taggedOutputReceiver(DoFn<InputT, OutputT> doFn) {
      return taggedOutputReceiver;
    }

    @Override
    public BundleFinalizer bundleFinalizer() {
      return bundleFinalizer;
    }

    @Override
    public Object restriction() {
      return currentRestriction;
    }

    @Override
    public DoFn<InputT, OutputT>.OnTimerContext onTimerContext(DoFn<InputT, OutputT> doFn) {
      throw new UnsupportedOperationException(
          "Cannot access OnTimerContext outside of @OnTimer methods.");
    }

    @Override
    public RestrictionTracker<?, ?> restrictionTracker() {
      return currentTracker;
    }

    @Override
    public PipelineOptions getPipelineOptions() {
      return pipelineOptions;
    }

    @Override
    public PipelineOptions pipelineOptions() {
      return pipelineOptions;
    }

    @Override
    public InputT element() {
      return currentElement.getValue();
    }

    @Override
    public Instant timestamp() {
      return currentElement.getTimestamp();
    }

    @Override
    public PaneInfo pane() {
      return currentElement.getPaneInfo();
    }

    @Override
    public Object watermarkEstimatorState() {
      return currentWatermarkEstimatorState;
    }

    @Override
    public WatermarkEstimator<?> watermarkEstimator() {
      return currentWatermarkEstimator;
    }
  }

  /**
   * Provides arguments for a {@link DoFnInvoker} for {@link
   * DoFn.OnWindowExpiration @OnWindowExpiration}.
   */
  private class OnWindowExpirationContext<K> extends BaseArgumentProvider<InputT, OutputT> {
    private class Context extends DoFn<InputT, OutputT>.OnWindowExpirationContext
        implements OutputReceiver<OutputT> {
      private Context() {
        doFn.super();
      }

      @Override
      public PipelineOptions getPipelineOptions() {
        return pipelineOptions;
      }

      @Override
      public BoundedWindow window() {
        return currentWindow;
      }

      @Override
      public void output(OutputT output) {
        outputTo(
            mainOutputConsumer,
            WindowedValues.of(
                output,
                currentTimer.getHoldTimestamp(),
                currentWindow,
                currentTimer.getPaneInfo()));
      }

      @Override
      public void outputWithTimestamp(OutputT output, Instant timestamp) {
        checkOnWindowExpirationTimestamp(timestamp);
        outputTo(
            mainOutputConsumer,
            WindowedValues.of(output, timestamp, currentWindow, currentTimer.getPaneInfo()));
      }

      @Override
      public void outputWindowedValue(
          OutputT output,
          Instant timestamp,
          Collection<? extends BoundedWindow> windows,
          PaneInfo paneInfo) {
        checkOnWindowExpirationTimestamp(timestamp);
        outputTo(mainOutputConsumer, WindowedValues.of(output, timestamp, windows, paneInfo));
      }

      @Override
      public <T> void output(TupleTag<T> tag, T output) {
        FnDataReceiver<WindowedValue<T>> consumer =
            (FnDataReceiver) localNameToConsumer.get(tag.getId());
        if (consumer == null) {
          throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
        }
        outputTo(
            consumer,
            WindowedValues.of(
                output,
                currentTimer.getHoldTimestamp(),
                currentWindow,
                currentTimer.getPaneInfo()));
      }

      @Override
      public <T> void outputWithTimestamp(TupleTag<T> tag, T output, Instant timestamp) {
        checkOnWindowExpirationTimestamp(timestamp);
        FnDataReceiver<WindowedValue<T>> consumer =
            (FnDataReceiver) localNameToConsumer.get(tag.getId());
        if (consumer == null) {
          throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
        }
        outputTo(
            consumer,
            WindowedValues.of(output, timestamp, currentWindow, currentTimer.getPaneInfo()));
      }

      @Override
      public <T> void outputWindowedValue(
          TupleTag<T> tag,
          T output,
          Instant timestamp,
          Collection<? extends BoundedWindow> windows,
          PaneInfo paneInfo) {
        checkOnWindowExpirationTimestamp(timestamp);
        FnDataReceiver<WindowedValue<T>> consumer =
            (FnDataReceiver) localNameToConsumer.get(tag.getId());
        outputTo(consumer, WindowedValues.of(output, timestamp, windows, paneInfo));
      }

      @SuppressWarnings(
          "deprecation") // Allowed Skew is deprecated for users, but must be respected
      private void checkOnWindowExpirationTimestamp(Instant timestamp) {
        Instant lowerBound;
        try {
          lowerBound = currentTimer.getHoldTimestamp().minus(doFn.getAllowedTimestampSkew());
        } catch (ArithmeticException e) {
          lowerBound = BoundedWindow.TIMESTAMP_MIN_VALUE;
        }
        if (timestamp.isBefore(lowerBound)
            || timestamp.isAfter(BoundedWindow.TIMESTAMP_MAX_VALUE)) {
          throw new IllegalArgumentException(
              String.format(
                  "Cannot output with timestamp %s. Output timestamps must be no earlier than the "
                      + "timestamp of the timer (%s) minus the allowed skew (%s) and no later "
                      + "than %s. See the DoFn#getAllowedTimestampSkew() Javadoc for details on "
                      + "changing the allowed skew.",
                  timestamp,
                  currentTimer.getHoldTimestamp(),
                  doFn.getAllowedTimestampSkew().getMillis() >= Integer.MAX_VALUE
                      ? doFn.getAllowedTimestampSkew()
                      : PeriodFormat.getDefault().print(doFn.getAllowedTimestampSkew().toPeriod()),
                  BoundedWindow.TIMESTAMP_MAX_VALUE));
        }
      }
    }

    private final OnWindowExpirationContext.Context context =
        new OnWindowExpirationContext.Context();

    @Override
    public BoundedWindow window() {
      return currentWindow;
    }

    @Override
    public Instant timestamp(DoFn<InputT, OutputT> doFn) {
      return currentTimer.getHoldTimestamp();
    }

    @Override
    public TimeDomain timeDomain(DoFn<InputT, OutputT> doFn) {
      return currentTimeDomain;
    }

    @Override
    public K key() {
      return (K) currentTimer.getUserKey();
    }

    @Override
    public OutputReceiver<OutputT> outputReceiver(DoFn<InputT, OutputT> doFn) {
      return context;
    }

    private final OutputReceiver<Row> mainRowOutputReceiver =
        mainOutputSchemaCoder == null
            ? null
            : new OutputReceiver<Row>() {
              private final SerializableFunction<Row, OutputT> fromRowFunction =
                  mainOutputSchemaCoder.getFromRowFunction();

              @Override
              public void output(Row output) {
                context.output(fromRowFunction.apply(output));
              }

              @Override
              public void outputWithTimestamp(Row output, Instant timestamp) {
                context.outputWithTimestamp(fromRowFunction.apply(output), timestamp);
              }

              @Override
              public void outputWindowedValue(
                  Row output,
                  Instant timestamp,
                  Collection<? extends BoundedWindow> windows,
                  PaneInfo paneInfo) {
                context.outputWindowedValue(
                    fromRowFunction.apply(output), timestamp, windows, paneInfo);
              }
            };

    @Override
    public OutputReceiver<Row> outputRowReceiver(DoFn<InputT, OutputT> doFn) {
      checkState(
          mainOutputSchemaCoder != null,
          "Output with tag "
              + mainOutputTag
              + " must have a schema in order to call getRowReceiver");
      return mainRowOutputReceiver;
    }

    /** A {@link MultiOutputReceiver} which caches created instances to re-use across bundles. */
    private final MultiOutputReceiver taggedOutputReceiver =
        new MultiOutputReceiver() {
          private final Map<TupleTag<?>, OutputReceiver<?>> taggedOutputReceivers = new HashMap<>();
          private final Map<TupleTag<?>, OutputReceiver<Row>> taggedRowReceivers = new HashMap<>();

          private <T> OutputReceiver<T> createTaggedOutputReceiver(TupleTag<T> tag) {
            if (tag == null || mainOutputTag.equals(tag)) {
              return (OutputReceiver<T>) context;
            }
            return new OutputReceiver<T>() {
              @Override
              public void output(T output) {
                context.output(tag, output);
              }

              @Override
              public void outputWithTimestamp(T output, Instant timestamp) {
                context.outputWithTimestamp(tag, output, timestamp);
              }

              @Override
              public void outputWindowedValue(
                  T output,
                  Instant timestamp,
                  Collection<? extends BoundedWindow> windows,
                  PaneInfo paneInfo) {
                context.outputWindowedValue(tag, output, timestamp, windows, paneInfo);
              }
            };
          }

          private <T> OutputReceiver<Row> createTaggedRowReceiver(TupleTag<T> tag) {
            if (tag == null || mainOutputTag.equals(tag)) {
              checkState(
                  mainOutputSchemaCoder != null,
                  "Output with tag "
                      + mainOutputTag
                      + " must have a schema in order to call getRowReceiver");
              return mainRowOutputReceiver;
            }

            Coder<T> outputCoder = (Coder<T>) outputCoders.get(tag);
            checkState(outputCoder != null, "No output tag for " + tag);
            checkState(
                outputCoder instanceof SchemaCoder,
                "Output with tag " + tag + " must have a schema in order to call getRowReceiver");
            return new OutputReceiver<Row>() {
              private SerializableFunction<Row, T> fromRowFunction =
                  ((SchemaCoder) outputCoder).getFromRowFunction();

              @Override
              public void output(Row output) {
                context.output(tag, fromRowFunction.apply(output));
              }

              @Override
              public void outputWithTimestamp(Row output, Instant timestamp) {
                context.outputWithTimestamp(tag, fromRowFunction.apply(output), timestamp);
              }

              @Override
              public void outputWindowedValue(
                  Row output,
                  Instant timestamp,
                  Collection<? extends BoundedWindow> windows,
                  PaneInfo paneInfo) {
                context.outputWindowedValue(
                    tag, fromRowFunction.apply(output), timestamp, windows, paneInfo);
              }
            };
          }

          @Override
          public <T> OutputReceiver<T> get(TupleTag<T> tag) {
            return (OutputReceiver<T>)
                taggedOutputReceivers.computeIfAbsent(tag, this::createTaggedOutputReceiver);
          }

          @Override
          public <T> OutputReceiver<Row> getRowReceiver(TupleTag<T> tag) {
            return taggedRowReceivers.computeIfAbsent(tag, this::createTaggedRowReceiver);
          }
        };

    @Override
    public MultiOutputReceiver taggedOutputReceiver(DoFn<InputT, OutputT> doFn) {
      return taggedOutputReceiver;
    }

    @Override
    public State state(String stateId, boolean alwaysFetched) {
      StateDeclaration stateDeclaration = doFnSignature.stateDeclarations().get(stateId);
      checkNotNull(stateDeclaration, "No state declaration found for %s", stateId);
      StateSpec<?> spec;
      try {
        spec = (StateSpec<?>) stateDeclaration.field().get(doFn);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
      State state = spec.bind(stateId, stateAccessor);
      if (alwaysFetched) {
        return (State) ((ReadableState) state).readLater();
      } else {
        return state;
      }
    }

    @Override
    public PipelineOptions pipelineOptions() {
      return pipelineOptions;
    }

    @Override
    public String getErrorContext() {
      return "FnApiDoFnRunner/OnWindowExpiration";
    }
  }

  /** Provides arguments for a {@link DoFnInvoker} for {@link DoFn.OnTimer @OnTimer}. */
  private class OnTimerContext<K> extends BaseArgumentProvider<InputT, OutputT> {

    private class Context extends DoFn<InputT, OutputT>.OnTimerContext
        implements OutputReceiver<OutputT> {
      private Context() {
        doFn.super();
      }

      @Override
      public PipelineOptions getPipelineOptions() {
        return pipelineOptions;
      }

      @Override
      public BoundedWindow window() {
        return currentWindow;
      }

      @Override
      public void output(OutputT output) {
        checkTimerTimestamp(currentTimer.getHoldTimestamp());
        outputTo(
            mainOutputConsumer,
            WindowedValues.of(
                output,
                currentTimer.getHoldTimestamp(),
                currentWindow,
                currentTimer.getPaneInfo()));
      }

      @Override
      public void outputWithTimestamp(OutputT output, Instant timestamp) {
        checkTimerTimestamp(timestamp);
        outputTo(
            mainOutputConsumer,
            WindowedValues.of(output, timestamp, currentWindow, currentTimer.getPaneInfo()));
      }

      @Override
      public void outputWindowedValue(
          OutputT output,
          Instant timestamp,
          Collection<? extends BoundedWindow> windows,
          PaneInfo paneInfo) {
        checkTimerTimestamp(timestamp);
        outputTo(mainOutputConsumer, WindowedValues.of(output, timestamp, windows, paneInfo));
      }

      @Override
      public <T> void output(TupleTag<T> tag, T output) {
        checkTimerTimestamp(currentTimer.getHoldTimestamp());
        FnDataReceiver<WindowedValue<T>> consumer =
            (FnDataReceiver) localNameToConsumer.get(tag.getId());
        if (consumer == null) {
          throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
        }
        outputTo(
            consumer,
            WindowedValues.of(
                output,
                currentTimer.getHoldTimestamp(),
                currentWindow,
                currentTimer.getPaneInfo()));
      }

      @Override
      public <T> void outputWithTimestamp(TupleTag<T> tag, T output, Instant timestamp) {
        checkTimerTimestamp(timestamp);
        FnDataReceiver<WindowedValue<T>> consumer =
            (FnDataReceiver) localNameToConsumer.get(tag.getId());
        if (consumer == null) {
          throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
        }
        outputTo(
            consumer,
            WindowedValues.of(output, timestamp, currentWindow, currentTimer.getPaneInfo()));
      }

      @Override
      public <T> void outputWindowedValue(
          TupleTag<T> tag,
          T output,
          Instant timestamp,
          Collection<? extends BoundedWindow> windows,
          PaneInfo paneInfo) {}

      @Override
      public TimeDomain timeDomain() {
        return currentTimeDomain;
      }

      @Override
      public Instant fireTimestamp() {
        return currentTimer.getFireTimestamp();
      }

      @Override
      public Instant timestamp() {
        return currentTimer.getHoldTimestamp();
      }

      @SuppressWarnings(
          "deprecation") // Allowed Skew is deprecated for users, but must be respected
      private void checkTimerTimestamp(Instant timestamp) {
        Instant lowerBound;
        try {
          lowerBound = currentTimer.getHoldTimestamp().minus(doFn.getAllowedTimestampSkew());
        } catch (ArithmeticException e) {
          lowerBound = BoundedWindow.TIMESTAMP_MIN_VALUE;
        }
        if (timestamp.isBefore(lowerBound)
            || timestamp.isAfter(BoundedWindow.TIMESTAMP_MAX_VALUE)) {
          throw new IllegalArgumentException(
              String.format(
                  "Cannot output with timestamp %s. Output timestamps must be no earlier than the "
                      + "timestamp of the timer (%s) minus the allowed skew (%s) and no later "
                      + "than %s. See the DoFn#getAllowedTimestampSkew() Javadoc for details on "
                      + "changing the allowed skew.",
                  timestamp,
                  currentTimer.getHoldTimestamp(),
                  doFn.getAllowedTimestampSkew().getMillis() >= Integer.MAX_VALUE
                      ? doFn.getAllowedTimestampSkew()
                      : PeriodFormat.getDefault().print(doFn.getAllowedTimestampSkew().toPeriod()),
                  BoundedWindow.TIMESTAMP_MAX_VALUE));
        }
      }
    }

    private final OnTimerContext.Context context = new OnTimerContext.Context();

    @Override
    public BoundedWindow window() {
      return currentWindow;
    }

    @Override
    public Instant timestamp(DoFn<InputT, OutputT> doFn) {
      return currentTimer.getHoldTimestamp();
    }

    @Override
    public TimeDomain timeDomain(DoFn<InputT, OutputT> doFn) {
      return currentTimeDomain;
    }

    @Override
    public K key() {
      return (K) currentTimer.getUserKey();
    }

    @Override
    public OutputReceiver<OutputT> outputReceiver(DoFn<InputT, OutputT> doFn) {
      return context;
    }

    private final OutputReceiver<Row> mainRowOutputReceiver =
        mainOutputSchemaCoder == null
            ? null
            : new OutputReceiver<Row>() {
              private final SerializableFunction<Row, OutputT> fromRowFunction =
                  mainOutputSchemaCoder.getFromRowFunction();

              @Override
              public void output(Row output) {
                context.outputWithTimestamp(
                    fromRowFunction.apply(output), currentElement.getTimestamp());
              }

              @Override
              public void outputWithTimestamp(Row output, Instant timestamp) {
                context.outputWithTimestamp(fromRowFunction.apply(output), timestamp);
              }

              @Override
              public void outputWindowedValue(
                  Row output,
                  Instant timestamp,
                  Collection<? extends BoundedWindow> windows,
                  PaneInfo paneInfo) {
                context.outputWindowedValue(
                    fromRowFunction.apply(output), timestamp, windows, paneInfo);
              }
            };

    @Override
    public OutputReceiver<Row> outputRowReceiver(DoFn<InputT, OutputT> doFn) {
      checkState(
          mainOutputSchemaCoder != null,
          "Output with tag "
              + mainOutputTag
              + " must have a schema in order to call getRowReceiver");
      return mainRowOutputReceiver;
    }

    /** A {@link MultiOutputReceiver} which caches created instances to re-use across bundles. */
    private final MultiOutputReceiver taggedOutputReceiver =
        new MultiOutputReceiver() {
          private final Map<TupleTag<?>, OutputReceiver<?>> taggedOutputReceivers = new HashMap<>();
          private final Map<TupleTag<?>, OutputReceiver<Row>> taggedRowReceivers = new HashMap<>();

          private <T> OutputReceiver<T> createTaggedOutputReceiver(TupleTag<T> tag) {
            if (tag == null || mainOutputTag.equals(tag)) {
              return (OutputReceiver<T>) context;
            }
            return new OutputReceiver<T>() {
              @Override
              public void output(T output) {
                context.output(tag, output);
              }

              @Override
              public void outputWithTimestamp(T output, Instant timestamp) {
                context.outputWithTimestamp(tag, output, timestamp);
              }

              @Override
              public void outputWindowedValue(
                  T output,
                  Instant timestamp,
                  Collection<? extends BoundedWindow> windows,
                  PaneInfo paneInfo) {
                context.outputWindowedValue(tag, output, timestamp, windows, paneInfo);
              }
            };
          }

          private <T> OutputReceiver<Row> createTaggedRowReceiver(TupleTag<T> tag) {
            if (tag == null || mainOutputTag.equals(tag)) {
              checkState(
                  mainOutputSchemaCoder != null,
                  "Output with tag "
                      + mainOutputTag
                      + " must have a schema in order to call getRowReceiver");
              return mainRowOutputReceiver;
            }

            Coder<T> outputCoder = (Coder<T>) outputCoders.get(tag);
            checkState(outputCoder != null, "No output tag for " + tag);
            checkState(
                outputCoder instanceof SchemaCoder,
                "Output with tag " + tag + " must have a schema in order to call getRowReceiver");
            return new OutputReceiver<Row>() {
              private SerializableFunction<Row, T> fromRowFunction =
                  ((SchemaCoder) outputCoder).getFromRowFunction();

              @Override
              public void output(Row output) {
                context.output(tag, fromRowFunction.apply(output));
              }

              @Override
              public void outputWithTimestamp(Row output, Instant timestamp) {
                context.outputWithTimestamp(tag, fromRowFunction.apply(output), timestamp);
              }

              @Override
              public void outputWindowedValue(
                  Row output,
                  Instant timestamp,
                  Collection<? extends BoundedWindow> windows,
                  PaneInfo paneInfo) {
                context.outputWindowedValue(
                    tag, fromRowFunction.apply(output), timestamp, windows, paneInfo);
              }
            };
          }

          @Override
          public <T> OutputReceiver<T> get(TupleTag<T> tag) {
            return (OutputReceiver<T>)
                taggedOutputReceivers.computeIfAbsent(tag, this::createTaggedOutputReceiver);
          }

          @Override
          public <T> OutputReceiver<Row> getRowReceiver(TupleTag<T> tag) {
            return taggedRowReceivers.computeIfAbsent(tag, this::createTaggedRowReceiver);
          }
        };

    @Override
    public MultiOutputReceiver taggedOutputReceiver(DoFn<InputT, OutputT> doFn) {
      return taggedOutputReceiver;
    }

    @Override
    public DoFn<InputT, OutputT>.OnTimerContext onTimerContext(DoFn<InputT, OutputT> doFn) {
      return context;
    }

    @Override
    public State state(String stateId, boolean alwaysFetched) {
      StateDeclaration stateDeclaration = doFnSignature.stateDeclarations().get(stateId);
      checkNotNull(stateDeclaration, "No state declaration found for %s", stateId);
      StateSpec<?> spec;
      try {
        spec = (StateSpec<?>) stateDeclaration.field().get(doFn);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
      State state = spec.bind(stateId, stateAccessor);
      if (alwaysFetched) {
        return (State) ((ReadableState) state).readLater();
      } else {
        return state;
      }
    }

    @Override
    public org.apache.beam.sdk.state.Timer timer(String timerId) {
      TimeDomain timeDomain =
          translateTimeDomain(parDoPayload.getTimerFamilySpecsMap().get(timerId).getTimeDomain());
      return new FnApiTimer(
          timerId,
          currentTimer.getUserKey(),
          "",
          currentWindow,
          currentTimer.getHoldTimestamp(),
          currentTimer.getFireTimestamp(),
          currentTimer.getPaneInfo(),
          timeDomain);
    }

    @Override
    public TimerMap timerFamily(String timerFamilyId) {
      return new FnApiTimerMap(
          timerFamilyId,
          currentTimer.getUserKey(),
          currentWindow,
          currentTimer.getHoldTimestamp(),
          currentTimer.getFireTimestamp(),
          currentTimer.getPaneInfo());
    }

    @Override
    public String timerId(DoFn<InputT, OutputT> doFn) {
      // Timer id is aliased to dynamic timer tag in a TimerFamily timer.
      return currentTimer.getDynamicTimerTag();
    }

    @Override
    public PipelineOptions pipelineOptions() {
      return pipelineOptions;
    }

    @Override
    public String getErrorContext() {
      return "FnApiDoFnRunner/OnTimer";
    }
  }

  private TimeDomain translateTimeDomain(
      org.apache.beam.model.pipeline.v1.RunnerApi.TimeDomain.Enum domain) {
    switch (domain) {
      case EVENT_TIME:
        return TimeDomain.EVENT_TIME;
      case PROCESSING_TIME:
        return TimeDomain.PROCESSING_TIME;
      default:
        throw new IllegalArgumentException("Unknown time domain");
    }
  }
}
