package com.smart.android.ad_app.google.ima.local.portable;
import java.util.ArrayList;import java.util.List;import java.util.concurrent.*;
/** The completion/wait subset used by actual Android callbacks. No Java API24 CompletableFuture. */
public final class SimplePromise<V> implements Future<V> {
 public interface Completion<V>{void accept(V value,Throwable failure);}
 private boolean done,cancelled;private V value;private Throwable failure;private final List<Runnable> callbacks=new ArrayList<Runnable>();
 public boolean complete(V value){return finish(value,null,false);}public boolean completeExceptionally(Throwable error){if(error==null)throw new NullPointerException();return finish(null,error,false);}
 private boolean finish(V value,Throwable error,boolean cancel){List<Runnable> pending;synchronized(this){if(done)return false;this.value=value;failure=error;cancelled=cancel||error instanceof CancellationException;done=true;pending=new ArrayList<Runnable>(callbacks);callbacks.clear();notifyAll();}for(Runnable task:pending)task.run();return true;}
 public void whenCompleteAsync(final Completion<V> callback,final Executor executor){if(callback==null||executor==null)throw new NullPointerException();Runnable dispatch=new Runnable(){public void run(){try{executor.execute(new Runnable(){public void run(){V actual;Throwable error;synchronized(SimplePromise.this){actual=value;error=failure;}try{callback.accept(actual,error);}catch(Throwable ignored){/* dependent stage is unused by this closure */}}});}catch(Throwable ignored){/* failed dependent scheduling is not thrown by complete */}}};synchronized(this){if(!done){callbacks.add(dispatch);return;}}dispatch.run();}
 public boolean cancel(boolean mayInterruptIfRunning){if(finish(null,new CancellationException(),true))return true;return isCancelled();}public synchronized boolean isCancelled(){return cancelled;}public synchronized boolean isDone(){return done;}
 public V get()throws InterruptedException,ExecutionException{synchronized(this){while(!done)wait();return result();}}
 public V get(long timeout,TimeUnit unit)throws InterruptedException,ExecutionException,TimeoutException{if(unit==null)throw new NullPointerException();long remaining=unit.toNanos(timeout),deadline=System.nanoTime()+remaining;synchronized(this){while(!done){if(remaining<=0)throw new TimeoutException();TimeUnit.NANOSECONDS.timedWait(this,remaining);remaining=deadline-System.nanoTime();}return result();}}
 private V result()throws ExecutionException{if(cancelled)throw (CancellationException)failure;if(failure!=null){Throwable error=failure;if("java.util.concurrent.CompletionException".equals(error.getClass().getName())&&error.getCause()!=null)error=error.getCause();throw new ExecutionException(error);}return value;}
}
