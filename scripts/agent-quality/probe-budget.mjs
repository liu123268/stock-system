import assert from 'node:assert/strict'

function checkState(probe, expectedUnknownReservations) {
  assert.equal(probe.officialEndpoint, true)
  assert.equal(probe.budget.limitYuan, 10, '必须沿用原累计十元上限')
  assert.ok(Number.isSafeInteger(expectedUnknownReservations) && expectedUnknownReservations >= 0)
  assert.equal(probe.budget.unknownReservations, expectedUnknownReservations, '历史未知笔数必须与本轮显式认可的一致')
  assert.ok(Number.isFinite(probe.budget.occupiedUpperYuan) && probe.budget.occupiedUpperYuan >= 0)
}

export function checkBudgetStart(probe, expectedUnknownReservations) {
  checkState(probe, expectedUnknownReservations)
  assert.ok(probe.budget.occupiedUpperYuan + 4.016 <= 10, '剩余额度不足以预留一次有界运行')
}

export function checkBudgetAfter(before, after) {
  checkState(after, before.budget.unknownReservations)
  assert.ok(Number.isFinite(after.budget.occupiedUpperYuan) && after.budget.occupiedUpperYuan >= before.budget.occupiedUpperYuan,
    '原占用不能下降或释放旧未知预留')
  assert.ok(after.budget.occupiedUpperYuan <= 10)
}
