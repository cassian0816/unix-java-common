package exchange.antx.common.util;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.common.util.concurrent.Uninterruptibles;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

@Slf4j
public class FutureUtil {

    private FutureUtil() {
    }

    public static <T> void futureToResponse(
            ListenableFuture<T> future,
            StreamObserver<T> responseObserver) {
        Futures.addCallback(future, new FutureCallback<T>() {
            @Override
            public void onSuccess(T result) {
                responseObserver.onNext(result);
                responseObserver.onCompleted();
            }

            @Override
            public void onFailure(Throwable t) {
                if (t instanceof StatusException) {
                    responseObserver.onError(t);
                } else if (t instanceof StatusRuntimeException) {
                    responseObserver.onError(t);
                } else {
                    log.error("unknown error", t);
                    responseObserver.onError(
                            Status.UNKNOWN
                                    .withCause(t)
                                    .asRuntimeException());
                }
            }
        }, MoreExecutors.directExecutor());
    }

    public static <RequestT, ResponseT> ListenableFuture<ResponseT> toFuture(
            RequestT request,
            BiConsumer<RequestT, StreamObserver<ResponseT>> serverMethod) {
        try {
            final SettableFuture<ResponseT> future = SettableFuture.create();
            serverMethod.accept(request, new StreamObserver<>() {

                private ResponseT value;

                @Override
                public void onNext(ResponseT value) {
                    this.value = value;
                }

                @Override
                public void onError(Throwable t) {
                    future.setException(t);
                }

                @Override
                public void onCompleted() {
                    future.set(this.value);
                }
            });
            return future;
        } catch (Throwable t) {
            return Futures.immediateFailedFuture(t);
        }
    }

    public static <T> T getQuietly(Future<T> future) {
        Preconditions.checkNotNull(future, "future is null");
        try {
            return Uninterruptibles.getUninterruptibly(future);
        } catch (ExecutionException e) {
            if (e.getCause() != null) {
                Throwables.throwIfUnchecked(e.getCause());
                throw new UncheckedExecutionException(e.getCause());
            }
            throw new AssertionError(e);
        }
    }

}
