/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.api.stateselector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.infrastructure.async.SafeFutureAssert.assertThatSafeFuture;
import static tech.pegasys.teku.infrastructure.unsigned.UInt64.ZERO;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.api.exceptions.BadRequestException;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.spec.datastructures.state.AnchorPoint;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.storage.api.StorageQueryChannel;
import tech.pegasys.teku.storage.client.ChainHead;
import tech.pegasys.teku.storage.client.CombinedChainDataClient;
import tech.pegasys.teku.storage.client.RecentChainData;

public class StateSelectorFactoryTest {

  private final CombinedChainDataClient client = mock(CombinedChainDataClient.class);
  private final Spec spec = TestSpecFactory.createMinimalPhase0();
  private final DataStructureUtil data = new DataStructureUtil(spec);
  private final SpecMilestone milestone = spec.getGenesisSpec().getMilestone();
  private final BeaconState state = data.randomBeaconState();

  private final StateSelectorFactory factory = new StateSelectorFactory(spec, client);

  @Test
  public void headSelector_shouldGetBestState() throws ExecutionException, InterruptedException {
    final SignedBlockAndState blockAndState = data.randomSignedBlockAndState(10);
    final ChainHead chainHead = ChainHead.create(blockAndState);
    when(client.getChainHead()).thenReturn(Optional.of(chainHead));
    Optional<StateAndMetaData> result = factory.headSelector().getState().get();
    assertThat(result).contains(withMetaData(blockAndState.getState()));
  }

  @Test
  public void finalizedSelector_shouldGetFinalizedState()
      throws ExecutionException, InterruptedException {
    when(client.getLatestFinalized())
        .thenReturn(Optional.of(AnchorPoint.fromInitialState(spec, state)));
    Optional<StateAndMetaData> result = factory.finalizedSelector().getState().get();
    assertThat(result).contains(withMetaData(state));
  }

  @Test
  public void justifiedSelector_shouldGetJustifiedState()
      throws ExecutionException, InterruptedException {
    when(client.getJustifiedState()).thenReturn(SafeFuture.completedFuture(Optional.of(state)));
    Optional<StateAndMetaData> result = factory.justifiedSelector().getState().get();
    assertThat(result).contains(withMetaData(state));
    verify(client).getJustifiedState();
  }

  @Test
  public void genesisSelector_shouldGetStateAtSlotExact()
      throws ExecutionException, InterruptedException {
    when(client.getStateAtSlotExact(ZERO))
        .thenReturn(SafeFuture.completedFuture(Optional.of(state)));
    Optional<StateAndMetaData> result = factory.genesisSelector().getState().get();
    assertThat(result).contains(withMetaData(state));
    verify(client).getStateAtSlotExact(ZERO);
  }

  @Test
  public void forSlot_shouldGetStateAtSlotExact() throws ExecutionException, InterruptedException {
    final SignedBlockAndState blockAndState = data.randomSignedBlockAndState(15);
    final ChainHead chainHead = ChainHead.create(blockAndState);
    when(client.getChainHead()).thenReturn(Optional.of(chainHead));
    when(client.getStateAtSlotExact(state.getSlot(), chainHead.getRoot()))
        .thenReturn(SafeFuture.completedFuture(Optional.of(state)));
    Optional<StateAndMetaData> result = factory.forSlot(state.getSlot()).getState().get();
    assertThat(result).contains(withMetaData(state));
  }

  @Test
  public void forStateRoot_shouldGetStateAtSlotExact()
      throws ExecutionException, InterruptedException {
    when(client.getStateByStateRoot(state.hashTreeRoot()))
        .thenReturn(SafeFuture.completedFuture(Optional.of(state)));
    Optional<StateAndMetaData> result = factory.forStateRoot(state.hashTreeRoot()).getState().get();
    assertThat(result).contains(withMetaData(state));
    verify(client).getStateByStateRoot(state.hashTreeRoot());
  }

  @Test
  public void defaultStateSelector_shouldThrowBadRequestException() {
    assertThrows(BadRequestException.class, () -> factory.defaultStateSelector("a"));
  }

  @Test
  public void byBlockRootSelector_shouldThrowBadRequestException() {
    assertThrows(BadRequestException.class, () -> factory.byBlockRootStateSelector("a"));
  }

  @Test
  public void stateSelector_shouldReturnEmptyWhenPreForkChoice() {
    final StorageQueryChannel historicalChainData = mock(StorageQueryChannel.class);
    final RecentChainData recentChainData = mock(RecentChainData.class);
    final CombinedChainDataClient client1 =
        new CombinedChainDataClient(recentChainData, historicalChainData, spec);
    final StateSelectorFactory factory = new StateSelectorFactory(spec, client1);
    when(recentChainData.isPreGenesis()).thenReturn(false);
    when(recentChainData.isPreForkChoice()).thenReturn(true);
    final SafeFuture<Optional<StateAndMetaData>> future =
        factory.defaultStateSelector(ZERO.toString()).getState();
    assertThatSafeFuture(future).isCompletedWithEmptyOptional();
  }

  @Test
  public void defaultBlockSelector_shouldThrowBadRequestForBadHexState() {
    assertThrows(BadRequestException.class, () -> factory.defaultStateSelector("0xzz"));
  }

  private StateAndMetaData withMetaData(final BeaconState state) {
    return new StateAndMetaData(
        state, milestone, false, spec.isMilestoneSupported(SpecMilestone.BELLATRIX));
  }
}
