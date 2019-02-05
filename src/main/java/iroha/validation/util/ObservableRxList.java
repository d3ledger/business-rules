package iroha.validation.util;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import java.util.ArrayList;
import java.util.List;

public class ObservableRxList<T> {

  private final List<T> list;
  private final PublishSubject<T> subject;

  public ObservableRxList() {
    this.list = new ArrayList<T>();
    this.subject = PublishSubject.create();
  }

  public void add(T value) {
    list.add(value);
    subject.onNext(value);
  }

  public void remove(T value) {
    list.remove(value);
  }

  public Observable<T> getObservable() {
    return subject;
  }

  public T get(int index) {
    return list.get(index);
  }

  public int size() {
    return list.size();
  }
}
