/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.publisher;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Supplier;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Cancellation;
import reactor.core.MultiProducer;
import reactor.core.Producer;
import reactor.core.Receiver;
import reactor.core.Trackable;

/**
 * Splits the source sequence into possibly overlapping publishers.
 * 
 * @param <T> the value type
 */

/**
 * @see <a href="https://github.com/reactor/reactive-streams-commons">https://github.com/reactor/reactive-streams-commons</a>
 */
final class FluxWindow<T> extends FluxSource<T, Flux<T>> {

	final int size;
	
	final int skip;
	
	final Supplier<? extends Queue<T>> processorQueueSupplier;

	final Supplier<? extends Queue<UnicastProcessor<T>>> overflowQueueSupplier;

	public FluxWindow(Publisher<? extends T> source, int size, Supplier<? extends Queue<T>> processorQueueSupplier) {
		super(source);
		if (size <= 0) {
			throw new IllegalArgumentException("size > 0 required but it was " + size);
		}
		this.size = size;
		this.skip = size;
		this.processorQueueSupplier = Objects.requireNonNull(processorQueueSupplier, "processorQueueSupplier");
		this.overflowQueueSupplier = null; // won't be needed here
	}

	public FluxWindow(Publisher<? extends T> source, int size, int skip, Supplier<? extends Queue<T>> processorQueueSupplier,
			Supplier<? extends Queue<UnicastProcessor<T>>> overflowQueueSupplier) {
		super(source);
		if (size <= 0) {
			throw new IllegalArgumentException("size > 0 required but it was " + size);
		}
		if (skip <= 0) {
			throw new IllegalArgumentException("skip > 0 required but it was " + skip);
		}
		this.size = size;
		this.skip = skip;
		this.processorQueueSupplier = Objects.requireNonNull(processorQueueSupplier, "processorQueueSupplier");
		this.overflowQueueSupplier = Objects.requireNonNull(overflowQueueSupplier, "overflowQueueSupplier");
	}
	
	@Override
	public void subscribe(Subscriber<? super Flux<T>> s) {
		if (skip == size) {
			source.subscribe(new WindowExactSubscriber<>(s, size, processorQueueSupplier));
		} else
		if (skip > size) {
			source.subscribe(new WindowSkipSubscriber<>(s, size, skip, processorQueueSupplier));
		} else {
			Queue<UnicastProcessor<T>> overflowQueue;
			
			try {
				overflowQueue = overflowQueueSupplier.get();
			} catch (Throwable e) {
				Operators.error(s, Operators.onOperatorError(e));
				return;
			}
			
			if (overflowQueue == null) {
				Operators.error(s, Operators.onOperatorError(new
						NullPointerException("The overflowQueueSupplier returned a null queue")));
				return;
			}
			
			source.subscribe(new WindowOverlapSubscriber<>(s, size, skip, processorQueueSupplier, overflowQueue));
		}
	}

	static final class WindowExactSubscriber<T>
			implements Subscriber<T>, Subscription, Cancellation, Producer, Receiver,
			           MultiProducer, Trackable {
		
		final Subscriber<? super Flux<T>> actual;

		final Supplier<? extends Queue<T>> processorQueueSupplier;
		
		final int size;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<WindowExactSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(WindowExactSubscriber.class, "wip");

		volatile int once;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<WindowExactSubscriber> ONCE =
				AtomicIntegerFieldUpdater.newUpdater(WindowExactSubscriber.class, "once");

		int index;
		
		Subscription s;

		UnicastProcessor<T> window;
		
		boolean done;
		
		public WindowExactSubscriber(Subscriber<? super Flux<T>> actual, int size,
				Supplier<? extends Queue<T>> processorQueueSupplier) {
			this.actual = actual;
			this.size = size;
			this.processorQueueSupplier = processorQueueSupplier;
			this.wip = 1;
		}
		
		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;
				actual.onSubscribe(this);
			}
		}
		
		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t);
				return;
			}
			
			int i = index;

			UnicastProcessor<T> w = window;
			if (i == 0) {
				WIP.getAndIncrement(this);
				
				
				Queue<T> q;
				
				try {
					q = processorQueueSupplier.get();
				} catch (Throwable ex) {
					WIP.decrementAndGet(this);
					done = true;
					cancel();
					
					actual.onError(ex);
					return;
				}
				
				if (q == null) {
					WIP.decrementAndGet(this);
					done = true;
					cancel();
					
					actual.onError(new NullPointerException("The processorQueueSupplier returned a null queue"));
					return;
				}

				w = new UnicastProcessor<>(q, this);
				window = w;
				
				actual.onNext(w);
			}
			
			i++;
			
			w.onNext(t);
			
			if (i == size) {
				index = 0;
				window = null;
				w.onComplete();
			} else {
				index = i;
			}
		}
		
		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t);
				return;
			}
			Processor<T, T> w = window;
			if (w != null) {
				window = null;
				w.onError(t);
			}
			
			actual.onError(t);
		}
		
		@Override
		public void onComplete() {
			if (done) {
				return;
			}

			Processor<T, T> w = window;
			if (w != null) {
				window = null;
				w.onComplete();
			}
			
			actual.onComplete();
		}
		
		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				long u = Operators.multiplyCap(size, n);
				s.request(u);
			}
		}
		
		@Override
		public void cancel() {
			if (ONCE.compareAndSet(this, 0, 1)) {
				dispose();
			}
		}

		@Override
		public void dispose() {
			if (WIP.decrementAndGet(this) == 0) {
				s.cancel();
			}
		}

		@Override
		public Object downstream() {
			return actual;
		}

		@Override
		public boolean isStarted() {
			return s != null && !done;
		}

		@Override
		public boolean isTerminated() {
			return done;
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public Iterator<?> downstreams() {
			return Arrays.asList(window).iterator();
		}

		@Override
		public long downstreamCount() {
			return window != null ? 1L : 0L;
		}

		@Override
		public long expectedFromUpstream() {
			return size - index;
		}

		@Override
		public long limit() {
			return size;
		}
	}

	static final class WindowSkipSubscriber<T>
			implements Subscriber<T>, Subscription, Cancellation, Receiver, MultiProducer,
			           Producer, Trackable {
		
		final Subscriber<? super Flux<T>> actual;

		final Supplier<? extends Queue<T>> processorQueueSupplier;
		
		final int size;
		
		final int skip;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<WindowSkipSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(WindowSkipSubscriber.class, "wip");

		volatile int once;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<WindowSkipSubscriber> ONCE =
				AtomicIntegerFieldUpdater.newUpdater(WindowSkipSubscriber.class, "once");

		volatile int firstRequest;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<WindowSkipSubscriber> FIRST_REQUEST =
				AtomicIntegerFieldUpdater.newUpdater(WindowSkipSubscriber.class, "firstRequest");

		int index;
		
		Subscription s;

		UnicastProcessor<T> window;
		
		boolean done;
		
		public WindowSkipSubscriber(Subscriber<? super Flux<T>> actual, int size, int skip,
				Supplier<? extends Queue<T>> processorQueueSupplier) {
			this.actual = actual;
			this.size = size;
			this.skip = skip;
			this.processorQueueSupplier = processorQueueSupplier;
			this.wip = 1;
		}
		
		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;
				actual.onSubscribe(this);
			}
		}
		
		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t);
				return;
			}
			
			int i = index;

			UnicastProcessor<T> w = window;
			if (i == 0) {
				WIP.getAndIncrement(this);
				
				
				Queue<T> q;
				
				try {
					q = processorQueueSupplier.get();
				} catch (Throwable ex) {
					WIP.decrementAndGet(this);
					done = true;
					cancel();
					
					actual.onError(ex);
					return;
				}
				
				if (q == null) {
					WIP.decrementAndGet(this);
					done = true;
					cancel();
					
					actual.onError(new NullPointerException("The processorQueueSupplier returned a null queue"));
					return;
				}

				w = new UnicastProcessor<>(q, this);
				window = w;
				
				actual.onNext(w);
			}
			
			i++;
			
			if (w != null) {
				w.onNext(t);
			}
			
			if (i == size) {
				window = null;
				w.onComplete();
			}
			
			if (i == skip) {
				index = 0;
			} else {
				index = i;
			}
		}
		
		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t);
				return;
			}
			Processor<T, T> w = window;
			if (w != null) {
				window = null;
				w.onError(t);
			}
			
			actual.onError(t);
		}
		
		@Override
		public void onComplete() {
			if (done) {
				return;
			}

			Processor<T, T> w = window;
			if (w != null) {
				window = null;
				w.onComplete();
			}
			
			actual.onComplete();
		}
		
		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				if (firstRequest == 0 && FIRST_REQUEST.compareAndSet(this, 0, 1)) {
					long u = Operators.multiplyCap(size, n);
					long v = Operators.multiplyCap(skip - size, n - 1);
					long w = Operators.addCap(u, v);
					s.request(w);
				} else {
					long u = Operators.multiplyCap(skip, n);
					s.request(u);
				}
			}
		}
		
		@Override
		public void cancel() {
			if (ONCE.compareAndSet(this, 0, 1)) {
				dispose();
			}
		}

		@Override
		public void dispose() {
			if (WIP.decrementAndGet(this) == 0) {
				s.cancel();
			}
		}

		@Override
		public Object downstream() {
			return actual;
		}

		@Override
		public boolean isStarted() {
			return s != null && !done;
		}

		@Override
		public boolean isTerminated() {
			return done;
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public Iterator<?> downstreams() {
			return Arrays.asList(window).iterator();
		}

		@Override
		public long downstreamCount() {
			return window != null ? 1L : 0L;
		}

		@Override
		public long getCapacity() {
			return size;
		}

		@Override
		public long getPending() {
			return skip + size - index;
		}
	}

	static final class WindowOverlapSubscriber<T>
			implements Subscriber<T>, Subscription, Cancellation, Producer, MultiProducer,
			           Receiver, Trackable {

		final Subscriber<? super Flux<T>> actual;

		final Supplier<? extends Queue<T>> processorQueueSupplier;

		final Queue<UnicastProcessor<T>> queue;
		
		final int size;
		
		final int skip;

		final ArrayDeque<UnicastProcessor<T>> windows;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<WindowOverlapSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(WindowOverlapSubscriber.class, "wip");

		volatile int once;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<WindowOverlapSubscriber> ONCE =
				AtomicIntegerFieldUpdater.newUpdater(WindowOverlapSubscriber.class, "once");

		volatile int firstRequest;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<WindowOverlapSubscriber> FIRST_REQUEST =
				AtomicIntegerFieldUpdater.newUpdater(WindowOverlapSubscriber.class, "firstRequest");

		volatile long requested;
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<WindowOverlapSubscriber> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(WindowOverlapSubscriber.class, "requested");

		volatile int dw;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<WindowOverlapSubscriber> DW =
				AtomicIntegerFieldUpdater.newUpdater(WindowOverlapSubscriber.class, "dw");

		int index;
		
		int produced;
		
		Subscription s;
		
		volatile boolean done;
		Throwable error;
		
		volatile boolean cancelled;
		
		public WindowOverlapSubscriber(Subscriber<? super Flux<T>> actual, int size, int skip,
				Supplier<? extends Queue<T>> processorQueueSupplier,
				Queue<UnicastProcessor<T>> overflowQueue) {
			this.actual = actual;
			this.size = size;
			this.skip = skip;
			this.processorQueueSupplier = processorQueueSupplier;
			this.wip = 1;
			this.queue = overflowQueue;
			this.windows = new ArrayDeque<>();
		}
		
		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;
				actual.onSubscribe(this);
			}
		}
		
		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t);
				return;
			}
			
			int i = index;
			
			if (i == 0) {
				if (!cancelled) {
					WIP.getAndIncrement(this);
					
					
					Queue<T> q;
					
					try {
						q = processorQueueSupplier.get();
					} catch (Throwable ex) {
						WIP.decrementAndGet(this);
						done = true;
						cancel();
						
						actual.onError(ex);
						return;
					}
					
					if (q == null) {
						WIP.decrementAndGet(this);
						done = true;
						cancel();
						
						actual.onError(new NullPointerException("The processorQueueSupplier returned a null queue"));
						return;
					}

					UnicastProcessor<T> w = new UnicastProcessor<>(q, this);
					
					windows.offer(w);
					
					queue.offer(w);
					drain();
				}
			}
			
			i++;

			for (Processor<T, T> w : windows) {
				w.onNext(t);
			}
			
			int p = produced + 1;
			if (p == size) {
				produced = p - skip;
				
				Processor<T, T> w = windows.poll();
				if (w != null) {
					w.onComplete();
				}
			} else {
				produced = p;
			}
			
			if (i == skip) {
				index = 0;
			} else {
				index = i;
			}
		}
		
		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t);
				return;
			}

			for (Processor<T, T> w : windows) {
				w.onError(t);
			}
			windows.clear();
			
			error = t;
			done = true;
			drain();
		}
		
		@Override
		public void onComplete() {
			if (done) {
				return;
			}

			for (Processor<T, T> w : windows) {
				w.onComplete();
			}
			windows.clear();
			
			done = true;
			drain();
		}

		void drain() {
			if (DW.getAndIncrement(this) != 0) {
				return;
			}
			
			final Subscriber<? super Flux<T>> a = actual;
			final Queue<UnicastProcessor<T>> q = queue;
			int missed = 1;
			
			for (;;) {
				
				long r = requested;
				long e = 0;
				
				while (e != r) {
					boolean d = done;

					UnicastProcessor<T> t = q.poll();
					
					boolean empty = t == null;
					
					if (checkTerminated(d, empty, a, q)) {
						return;
					}
					
					if (empty) {
						break;
					}
					
					a.onNext(t);
					
					e++;
				}
				
				if (e == r) {
					if (checkTerminated(done, q.isEmpty(), a, q)) {
						return;
					}
				}
				
				if (e != 0L && r != Long.MAX_VALUE) {
					REQUESTED.addAndGet(this, -e);
				}
				
				missed = DW.addAndGet(this, -missed);
				if (missed == 0) {
					break;
				}
			}
		}
		
		boolean checkTerminated(boolean d, boolean empty, Subscriber<?> a, Queue<?> q) {
			if (cancelled) {
				q.clear();
				return true;
			}
			
			if (d) {
				Throwable e = error;
				
				if (e != null) {
					q.clear();
					a.onError(e);
					return true;
				} else
				if (empty) {
					a.onComplete();
					return true;
				}
			}
			
			return false;
		}
		
		@Override
		public void request(long n) {
			if (Operators.validate(n)) {

				Operators.getAndAddCap(REQUESTED, this, n);

				if (firstRequest == 0 && FIRST_REQUEST.compareAndSet(this, 0, 1)) {
					long u = Operators.multiplyCap(skip, n - 1);
					long v = Operators.addCap(size, u);
					s.request(v);
				} else {
					long u = Operators.multiplyCap(skip, n);
					s.request(u);
				}

				drain();
			}
		}
		
		@Override
		public void cancel() {
			cancelled = true;
			if (ONCE.compareAndSet(this, 0, 1)) {
				dispose();
			}
		}

		@Override
		public void dispose() {
			if (WIP.decrementAndGet(this) == 0) {
				s.cancel();
			}
		}

		@Override
		public Object downstream() {
			return actual;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		@Override
		public boolean isStarted() {
			return s != null && !done && !cancelled;
		}

		@Override
		public boolean isTerminated() {
			return done;
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public Throwable getError() {
			return error;
		}

		@Override
		public long expectedFromUpstream() {
			return (size + skip) - produced;
		}

		@Override
		public long limit() {
			return skip;
		}

		@Override
		public Iterator<?> downstreams() {
			return Arrays.asList(windows.toArray())
			             .iterator();
		}

		@Override
		public long downstreamCount() {
			return windows.size();
		}

		@Override
		public long getCapacity() {
			return size;
		}

		@Override
		public long getPending() {
			return size - produced;
		}
	}

}
