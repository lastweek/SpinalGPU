package spinalgpu

import spinal.core._

object RoundRobinSelect {
  def firstFromBase(base: UInt, candidates: Bits, count: Int, idWidth: Int): (Bool, UInt) = {
    require(count > 0, "round-robin selection requires at least one candidate")

    val selectedValid = Bool()
    val selectedId = UInt(idWidth bits)

    if (count == 1) {
      selectedValid := candidates(0)
      selectedId := U(0, idWidth bits)
    } else {
      val candidateHits = Bits(count bits)
      val candidateIds = Vec(UInt(idWidth bits), count)

      selectedId := base.resized

      for (offset <- 0 until count) {
        val candidateWide = UInt((idWidth + 1) bits)
        candidateWide := base.resize(idWidth + 1) + U(offset, idWidth + 1 bits)

        val candidate = UInt(idWidth bits)
        candidate := candidateWide.resized
        when(candidateWide >= U(count, idWidth + 1 bits)) {
          candidate := (candidateWide - U(count, idWidth + 1 bits)).resized
        }

        candidateIds(offset) := candidate
        candidateHits(offset) := candidates(candidate)
      }

      selectedValid := candidateHits.orR
      for (offset <- 0 until count) {
        val earlierCandidateHit =
          if (offset == 0) False else candidateHits(offset - 1 downto 0).orR
        when(candidateHits(offset) && !earlierCandidateHit) {
          selectedId := candidateIds(offset)
        }
      }
    }

    (selectedValid, selectedId)
  }

  def first(candidates: Bits, count: Int, idWidth: Int): (Bool, UInt) =
    firstFromBase(U(0, idWidth bits), candidates, count, idWidth)

  def nextAfter(selectedId: UInt, count: Int, idWidth: Int): UInt = {
    require(count > 0, "round-robin increment requires at least one candidate")

    val nextId = UInt(idWidth bits)
    if (count == 1) {
      nextId := U(0, idWidth bits)
    } else {
      val nextWide = UInt((idWidth + 1) bits)
      nextWide := selectedId.resize(idWidth + 1) + U(1, idWidth + 1 bits)
      nextId := nextWide.resized
      when(nextWide >= U(count, idWidth + 1 bits)) {
        nextId := (nextWide - U(count, idWidth + 1 bits)).resized
      }
    }

    nextId
  }
}
