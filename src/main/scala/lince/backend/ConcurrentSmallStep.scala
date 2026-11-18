package lince.backend

import caos.sos.SOS
import lince.backend.Eval.Valuation
import lince.backend.Stream
import Stream.Streams
import lince.syntax.Lince.*
import Program.*

/**
 * Concurrent small-step semantics.
 *
 * - coordinates multiple named programs
 * - schedules instantaneous steps deterministically
 * - evolves active differential equations simultaneously
 * - delegates ordinary program steps to BasicSmallStep
 */
object ConcurrentSmallStep
    extends SOS[Action, ConcurrentSmallStep.ConcurrentState]:

  case class ConcurrentState(
      progs: Map[String, Program],
      order: List[String],
      v: Valuation,
      s: Streams,
      t: Double,
      lp: Int
  )

  val defaultRKSamples = 100

  override def accepting(
      s: ConcurrentState
  ): Boolean =
    s.t <= 0 || s.lp <= 0

  def next[A >: Action](
      st: ConcurrentState
  ): Set[(A, ConcurrentState)] =
    step(st)(using defaultRKSamples).toSet



  /**
   * Collect all locations read by an expression.
   */
  private def dependencies(
      e: Expr
  ): Set[Location] =

    e match
      case Expr.Num(_) =>
        Set.empty

      case Expr.True =>
        Set.empty

      case Expr.False =>
        Set.empty

      case Expr.Var(loc) =>
        Set(loc)

      case Expr.Func(_, es) =>
        es.flatMap(dependencies).toSet
  /**
   * Checks whether every variable required by an expression
   * already exists in the current state.
   */
  private def dependenciesAvailable(
      e: Expr,
      st: ConcurrentState
  ): Boolean =

    dependencies(e).forall { loc =>

      val variableAvailable =
        st.v.contains(loc)

      val streamAvailable =
        loc.prog.isEmpty &&
          st.s.contains(loc.name)

      variableAvailable || streamAvailable
    }

  /**
   * Checks whether the next statement of a program is an
   * executable instantaneous statement.
   *
   * Continuous statements are deliberately excluded because
   * they are handled together by stepContinuous.
   */
  private def instantaneousExecutable(
      prog: Program,
      st: ConcurrentState
  ): Boolean =

    nextWithRest(prog)._1 match

      case Skip =>
        false

      case EqDiff(_, _) =>
        false

      case Assign(_, e) =>
        dependenciesAvailable(e, st)

      case StreamDef(_, _) =>
        true

      case ITE(b, _, _) =>
        dependenciesAvailable(b, st)

      case While(b, _) =>
        dependenciesAvailable(b, st)

      case _ =>
        false
  

  /**
   * Selects the first executable instantaneous component
   * according to source order.
   *
   * Components whose next statement depends on unavailable
   * state are skipped temporarily.
   */
  private def scheduledProgram(
      st: ConcurrentState
  ): Option[(String, Program)] =

    st.order.iterator
      .flatMap { name =>

        st.progs
          .get(name)
          .map { prog =>
            name -> prog
          }
      }
      .find { case (_, prog) =>
        instantaneousExecutable(
          prog,
          st
        )
      }

  private def blockedInstantaneousPrograms(
      st: ConcurrentState
  ): List[(String, Set[Location])] =

    st.order.flatMap { name =>

      st.progs.get(name).flatMap { prog =>

        nextWithRest(prog)._1 match

          case Assign(_, e) =>
            val missing =
              dependencies(e).filterNot { loc =>

                st.v.contains(loc) ||
                (
                  loc.prog.isEmpty &&
                  st.s.contains(loc.name)
                )
              }

            if missing.nonEmpty then
              Some(name -> missing)
            else
              None

          case ITE(b, _, _) =>
            val missing =
              dependencies(b).filterNot { loc =>

                st.v.contains(loc) ||
                (
                  loc.prog.isEmpty &&
                  st.s.contains(loc.name)
                )
              }

            if missing.nonEmpty then
              Some(name -> missing)
            else
              None

          case While(b, _) =>
            val missing =
              dependencies(b).filterNot { loc =>

                st.v.contains(loc) ||
                (
                  loc.prog.isEmpty &&
                  st.s.contains(loc.name)
                )
              }

            if missing.nonEmpty then
              Some(name -> missing)
            else
              None

          case _ =>
            None
      }
    }

  def step(
      st: ConcurrentState
  )(using rkSamples: Int)
      : Option[(Action, ConcurrentState)] =

    if st.t <= 0 || st.lp <= 0 then
      return None

    scheduledProgram(st) match

      case Some(_) =>
        stepOne(st)

      case None =>

        stepContinuous(st) match

          case some @ Some(_) =>
            some

          case None =>

            val blocked =
              blockedInstantaneousPrograms(st)

            if blocked.nonEmpty then

              val details =
                blocked
                  .map { case (name, missing) =>

                    val component =
                      if name.isEmpty then
                        "main program"
                      else
                        s"program $name"

                    s"$component requires ${missing.mkString(", ")}"
                  }
                  .mkString("\n")

              sys.error(
                s"""[Concurrent] Execution cannot progress because of unresolved dependencies:
                  |$details""".stripMargin
              )

            else
              None

  /**
   * A currently active continuous evolution.
   *
   * dur = Some(d) means a finite remaining duration.
   * dur = None means an unbounded evolution.
   */
  private case class Flow(
      name: String,
      eqs: Map[Location, Expr],
      dur: Option[Double],
      rest: Program
  )

  private def stepContinuous(
      st: ConcurrentState
  )(using rkSamples: Int)
      : Option[(Action, ConcurrentState)] =

    given v0: Valuation = st.v

    var ss = st.s
    var failed = false

    val flows: List[Flow] =
      processOrder(st).flatMap { name =>

        st.progs.get(name).flatMap { p =>

          nextWithRest(p) match

            case (EqDiff(eqs, durExp), rest) =>

              val eqs2 =
                for (x, e) <- eqs yield
                  Eval.evalStreams(e, ss) match

                    case None =>
                      failed = true
                      x -> e

                    case Some((e2, ss2)) =>
                      ss = ss2
                      x -> e2

              val dur2: Option[Double] =
                durExp match

                  case None =>
                    None

                  case Some(d) =>
                    Eval.evalStreams(d, ss) match

                      case None =>
                        failed = true
                        None

                      case Some((d2, ss2)) =>
                        ss = ss2
                        Some(Eval.asDouble(d2))

              Some(
                Flow(
                  name,
                  eqs2,
                  dur2,
                  rest
                )
              )

            case _ =>
              None
        }
      }

    if failed || flows.isEmpty then
      None

    else

      /*
       * Only finite flows contribute to the next
       * continuous boundary.
       */
      val finiteDurations =
        flows.flatMap(_.dur)

      val minDur: Option[Double] =
        finiteDurations.minOption

      /*
       * Evolve either until:
       *
       *   - the first finite flow finishes, or
       *   - the global simulation time expires.
       *
       * If every flow is infinite, evolve until st.t.
       */
      val realDur =
        minDur match
          case Some(d) =>
            d.min(st.t)

          case None =>
            st.t

      val mergedEqs: Map[Location, Expr] =
        flows
          .flatMap(_.eqs)
          .toMap

      val v2 =
        RungeKutta(
          st.v,
          mergedEqs,
          realDur,
          rkSamples
        )

      val newProgs =
        st.progs.map {
          case (name, oldProg) =>

            flows.find(_.name == name) match

              case None =>
                name -> oldProg

              case Some(flow) =>

                flow.dur match

                  /*
                   * Infinite ODE:
                   * it remains active after this
                   * continuous interval.
                   */
                  case None =>
                    name ->
                      mkSeq(
                        EqDiff(
                          flow.eqs,
                          None
                        ) ::
                          flattenSeq(flow.rest)
                      )

                  /*
                   * Finite ODE.
                   */
                  case Some(dur) =>
                    val remaining =
                      dur - realDur

                    if remaining <= 0 then
                      name -> flow.rest

                    else
                      name ->
                        mkSeq(
                          EqDiff(
                            flow.eqs,
                            Some(
                              Expr.Num(remaining)
                            )
                          ) ::
                            flattenSeq(flow.rest)
                        )
        }

      val st2 =
        st.copy(
          progs = newProgs,
          v = v2,
          s = ss,
          t = st.t - realDur
        )

      val action =
        minDur match

          /*
           * No finite ODE exists, so the only thing
           * stopping us is the global time bound.
           */
          case None =>
            Action.DiffStop(
              mergedEqs,
              realDur
            )

          case Some(d) if d > st.t =>
            Action.DiffStop(
              mergedEqs,
              realDur
            )

          case Some(_) =>
            Action.DiffSkip(
              mergedEqs,
              realDur
            )

      Some(
        action -> st2
      )

  def stepOne(
      st: ConcurrentState
  )(using rkSamples: Int)
      : Option[(Action, ConcurrentState)] =

    scheduledProgram(st) match

      case Some((name, prog)) =>

        stepProgram(
          name,
          prog,
          st
        )(using rkSamples)

      case None =>
        None

  /**
   * Delegate one component's ordinary small step to
   * the sequential semantics.
   */
  def stepProgram(
      name: String,
      prog: Program,
      st: ConcurrentState
  )(using rkSamples: Int)
      : Option[(Action, ConcurrentState)] =

    prog match

      case Skip =>
        None

      case _ =>

        val basic =
          BasicSmallStep.BasicState(
            prog,
            st.v,
            st.s,
            st.t,
            st.lp
          )

        BasicSmallStep
          .step(basic)(using rkSamples)
          .map {
            case (a, basic2) =>

              val updated =
                st.copy(
                  progs =
                    st.progs.updated(
                      name,
                      basic2.p
                    ),
                  v = basic2.v,
                  s = basic2.s,
                  t = basic2.t,
                  lp = basic2.lp
                )

              a -> updated
          }

  private def flattenSeq(
      p: Program
  ): List[Program] =

    p match
      case Skip =>
        Nil

      case Seq(p, q) =>
        flattenSeq(p) ++
          flattenSeq(q)

      case _ =>
        List(p)

  private def mkSeq(
      ps: List[Program]
  ): Program =

    ps match
      case Nil =>
        Skip

      case h :: Nil =>
        h

      case h :: t =>
        Seq(
          h,
          mkSeq(t)
        )

  private def nextWithRest(
      p: Program
  ): (Program, Program) =

    flattenSeq(p) match
      case Nil =>
        Skip -> Skip

      case h :: t =>
        h -> mkSeq(t)

  private def processOrder(
      st: ConcurrentState
  ): List[String] =
    st.order