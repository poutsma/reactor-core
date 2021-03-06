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

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.Fuseable;
import reactor.core.scheduler.TimedScheduler;
import reactor.util.function.Tuple2;

/**
 * @author Stephane Maldini
 */
public class MonoElapsed<T> extends MonoSource<T, Tuple2<Long, T>> implements Fuseable {

	final TimedScheduler scheduler;

	public MonoElapsed(Publisher<T> source, TimedScheduler scheduler) {
		super(source);
		this.scheduler = scheduler;
	}

	@Override
	public void subscribe(Subscriber<? super Tuple2<Long, T>> s) {
		source.subscribe(new FluxElapsed.ElapsedSubscriber<T>(s, scheduler));
	}
}
