/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.util.amazon;

import com.amazonaws.client.builder.ExecutorFactory;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.transfer.Transfer;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author vbedrosova
 */
public final class S3Util {

  public static final String S3_THREAD_POOL_SIZE = "amazon.s3.transferManager.threadPool.size";
  public static final int DEFAULT_S3_THREAD_POOL_SIZE = 10;

  public interface WithTransferManager<T extends Transfer, E extends Throwable> {
    @NotNull
    Collection<T> run(@NotNull TransferManager manager) throws E;
  }

  public interface InterruptAwareWithTransferManager<T extends Transfer, E extends Throwable> extends WithTransferManager<T, E> {
    boolean isInterrupted();
  }

  public static <T extends Transfer, E extends Throwable> Collection<T> withTransferManager(@NotNull AmazonS3Client s3Client, @NotNull final WithTransferManager<T, E> withTransferManager) throws E {
    return withTransferManager(s3Client, false, createDefaultExecutorService(), withTransferManager);
  }

  private static <T extends Transfer, E extends Throwable> Collection<T> withTransferManager(@NotNull AmazonS3Client s3Client, final boolean shutdownClient,
                                                                                             @NotNull final ExecutorService executorService,
                                                                                             @NotNull final WithTransferManager<T, E> withTransferManager) throws E {
    final Collection<T> result = new ArrayList<T>();
    final AtomicReference<TransferManager> manager = new AtomicReference<TransferManager>(); // will actually be created after handleInterrupt in order to make sure there are threads
    final AtomicBoolean isInterrupted = handleInterrupt(shutdownClient, executorService, withTransferManager, manager);
    manager.set(TransferManagerBuilder.standard().withS3Client(s3Client).withExecutorFactory(createExecutorFactory(executorService)).withShutDownThreadPools(true).build());

    try {
      if (!isInterrupted.get()) {
        final Collection<T> transfers = withTransferManager.run(manager.get());

        for (T t : transfers) {
          try {
            t.waitForCompletion();
            result.add(t);
          } catch (InterruptedException e) {
             // noop
          } catch (Throwable e) {
            if (!isInterrupted.get()) {
              throw new RuntimeException(e);
            }
          }
        }
      }
    } finally {
      manager.get().shutdownNow(shutdownClient);
    }
    return result;
  }

  @NotNull
  private static <T extends Transfer, E extends Throwable> AtomicBoolean handleInterrupt(final boolean shutdownClient, @NotNull ExecutorService executorService,
                                                                                         @NotNull final WithTransferManager<T, E> withTransferManager, @NotNull final AtomicReference<TransferManager> manager) {
    final AtomicBoolean isInterrupted = new AtomicBoolean(false);
    if (withTransferManager instanceof InterruptAwareWithTransferManager) {
      final InterruptAwareWithTransferManager interruptAwareWithTransferManager = (InterruptAwareWithTransferManager) withTransferManager;
      executorService.submit(new Runnable() {
        @Override
        public void run() {
          try {
            while (!interruptAwareWithTransferManager.isInterrupted()) {
              Thread.sleep(1000);
            }
          } catch (InterruptedException e) {
            // noop
          }
          if (interruptAwareWithTransferManager.isInterrupted()) {
            isInterrupted.set(true);
            manager.get().shutdownNow(shutdownClient);
          }
        }
      });
    }
    return isInterrupted;
  }

  @NotNull
  private static ExecutorFactory createExecutorFactory(@NotNull final ExecutorService executorService) {
    return new ExecutorFactory() {
      @Override
      public ExecutorService newExecutor() {
        return executorService;
      }
    };
  }

  public static <T extends Transfer, E extends Throwable> Collection<T> withTransferManager(@NotNull Map<String, String> params, @NotNull final WithTransferManager<T, E> withTransferManager) throws E {
    return withTransferManager(params, createDefaultExecutorService(), withTransferManager);
  }

  private static <T extends Transfer, E extends Throwable> Collection<T> withTransferManager(@NotNull Map<String, String> params, @NotNull final ExecutorService executorService, @NotNull final WithTransferManager<T, E> withTransferManager) throws E {
    return AWSCommonParams.withAWSClients(params, new AWSCommonParams.WithAWSClients<Collection<T>, E>() {
      @NotNull
      @Override
      public Collection<T> run(@NotNull AWSClients clients) throws E {
        return withTransferManager(clients.createS3Client(), true, executorService, withTransferManager);
      }
    });
  }

  @NotNull
  public static ExecutorService createDefaultExecutorService() {
    final ThreadFactory threadFactory = new ThreadFactory() {
      private final AtomicInteger threadCount = new AtomicInteger(1);

      public Thread newThread(@NotNull Runnable r) {
        Thread thread = new Thread(r);
        thread.setName("amazon-util-s3-transfer-manager-worker-" + threadCount.getAndIncrement());
        return thread;
      }
    };
    return Executors.newFixedThreadPool(TeamCityProperties.getInteger(S3_THREAD_POOL_SIZE, DEFAULT_S3_THREAD_POOL_SIZE), threadFactory);
  }}