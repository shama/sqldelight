package com.squareup.sqldelight.runtime.rx

import app.cash.sqldelight.Query
import com.google.common.truth.Truth.assertThat
import com.squareup.sqldelight.runtime.rx.Employee.Companion.SELECT_EMPLOYEES
import com.squareup.sqldelight.runtime.rx.TestDb.Companion.TABLE_EMPLOYEE
import io.reactivex.schedulers.Schedulers
import org.junit.Test

class QueryObservableTest {
  @Test fun mapToListThrowsFromQueryRun() {
    val error = IllegalStateException("test exception")

    val query = object : Query<Any>({ throw AssertionError("Must not be called") }) {
      override fun execute() = throw error
      override fun addListener(listener: Listener) = throw error
      override fun removeListener(listener: Listener) = throw error
    }

    query.asObservable(Schedulers.trampoline()).mapToList()
      .test()
      .assertNoValues()
      .assertError(error)
  }

  @Test fun mapToListThrowsFromMapFunction() {
    val db = TestDb()
    val error = IllegalStateException("test exception")

    db.createQuery(TABLE_EMPLOYEE, SELECT_EMPLOYEES, { throw error })
      .asObservable(Schedulers.trampoline())
      .mapToList()
      .test()
      .assertNoValues()
      .assertError(error)

    db.close()
  }

  @Test fun `race between subscribing disposing observer does not leave orphan listeners`() {
    val queriesWithListeners = mutableListOf<Query.Listener>()

    val query = object : Query<Any>({ throw AssertionError("Must not be called") }) {
      override fun execute() = error("Must not be called")
      override fun addListener(listener: Listener) = error("Must not be called")
      override fun removeListener(listener: Listener) = error("Must not be called")
    }

    val subscriptionScheduler = NeverDisposedTestScheduler()

    query.asObservable(Schedulers.trampoline())
      .subscribeOn(subscriptionScheduler)
      .test()
      .dispose()

    subscriptionScheduler.triggerActions()

    assertThat(queriesWithListeners).isEmpty()
  }
}
