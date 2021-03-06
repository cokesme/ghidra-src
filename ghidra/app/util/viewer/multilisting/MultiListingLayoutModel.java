/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.util.viewer.multilisting;

import docking.widgets.fieldpanel.Layout;
import ghidra.app.util.viewer.field.DummyFieldFactory;
import ghidra.app.util.viewer.format.*;
import ghidra.app.util.viewer.listingpanel.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Program;
import ghidra.program.util.DiffUtility;
import ghidra.program.util.SimpleDiffUtility;
import ghidra.util.datastruct.WeakDataStructureFactory;
import ghidra.util.datastruct.WeakSet;
import ghidra.util.task.TaskMonitor;

/**
 * Class for creating multiple coordinated ListingModels for multiple programs.
 */

public class MultiListingLayoutModel implements ListingModelListener, FormatModelListener {

	private FormatManager formatMgr;
	private ListingModel[] models;
	private ListingModel[] alignedModels;
	private LayoutCache cache; // This maps the MultiLayouts to addresses from ListingModel[0].
	private WeakSet<ListingModelListener> listeners =
		WeakDataStructureFactory.createSingleThreadAccessWeakSet();
	private DummyFieldFactory emptyFactory;
	private AddressSetView primaryAddrSet; // This is compatible with program[0] and ListingModel[0].

	/**
	 * Constructs a new MultiListingLayoutModel.
	 * @param formatMgr the FormatManager used to layout the fields.
	 * @param programs the list of programs that will be coordinated using listing models.
	 * The first program in the array will be used as the primary program.
	 * @param primaryAddrSet the addressSet to use for the view. 
	 * This is compatible with the primary program, which is program[0].
	 */
	public MultiListingLayoutModel(FormatManager formatMgr, Program[] programs,
			AddressSetView primaryAddrSet) {
		this.formatMgr = formatMgr;
		this.primaryAddrSet = primaryAddrSet;
		this.emptyFactory = new DummyFieldFactory(formatMgr);
		cache = new LayoutCache();
		models = new ListingModel[programs.length];
		alignedModels = new ListingModel[programs.length];
		for (int programIndex = 0; programIndex < programs.length; programIndex++) {
			models[programIndex] = createListingModel(programs, programIndex);
			alignedModels[programIndex] = new AlignedModel(programIndex);
			models[programIndex].addListener(this);
		}
		formatMgr.addFormatModelListener(this);
	}

	private ListingModel createListingModel(Program[] programs, int programIndex) {
		ListingModel model = new ProgramBigListingModel(programs[programIndex], formatMgr);

		if (programIndex != 0) {
			// Get a converter for the model indicated by programIndex.
			model = new ListingModelConverter(models[0], model);
		}
		return model;
	}

	/**
	 * Returns the the ListingLayoutModel for the i'th program.
	 * @param index the index of program for which to return a listing model
	 */
	public ListingModel getAlignedModel(int index) {
		return alignedModels[index];
	}

	private void addLayoutListener(ListingModelListener listener) {
		listeners.add(listener);
	}

	private void removeLayoutListener(ListingModelListener listener) {
		listeners.remove(listener);
	}

	@Override
	public void dataChanged(boolean updateImmediately) {
		cache.clear();
		for (ListingModelListener listener : listeners) {
			listener.dataChanged(updateImmediately);
		}
	}

	@Override
	public void modelSizeChanged() {
		cache.clear();
		for (ListingModelListener listener : listeners) {
			listener.modelSizeChanged();
		}
	}

	private MultiLayout getMultiLayout(Address primaryModelAddress, boolean isGap) {
		// Check that this wasn't handed a null primaryModelAddress.
		// Some addresses (such as externals) can't have an equivalent primary address determined.
		if (primaryModelAddress == null) {
			return null;
		}
		MultiLayout ml = cache.get(primaryModelAddress);
		if (ml == null) {
			Layout[] layouts = new Layout[models.length];
			boolean hasLayout = false;
			for (int i = 0; i < models.length; i++) {
				layouts[i] = models[i].getLayout(primaryModelAddress, isGap);
				hasLayout |= layouts[i] != null;
			}
			if (hasLayout) {
				ml = new MultiLayout(layouts, formatMgr, emptyFactory);
			}
			else {
				ml = new MultiLayout();
			}
			cache.put(primaryModelAddress, ml);
		}
		if (ml.isEmpty()) {
			return null;
		}
		return ml;
	}

	class AlignedModel implements ListingModel {
		private int modelID;

		AlignedModel(int modelID) {
			this.modelID = modelID;
		}

		@Override
		public void dispose() {
			// should be handled by containing class
		}

		@Override
		public int getMaxWidth() {
			return models[modelID].getMaxWidth();
		}

		@Override
		public Address getAddressAfter(Address address) {
			Address nextAddress = null; // Next address for this model.
			Program program = getProgram();
			Program primaryProgram = models[0].getProgram();
			Address primaryModelAddress = (program == primaryProgram) ? address
					: SimpleDiffUtility.getCompatibleAddress(program, address, primaryProgram);
			// If address is an external from the other model, then we may not be able to get 
			// an equivalent address in the primary model (i.e. primaryModelAddress may be null).
			if (primaryModelAddress == null) {
				return null;
			}
			// Important: These models can be from different programs, so must convert addresses.
			for (ListingModel tempModel : models) {
				Address tempModelAddressAfter = tempModel.getAddressAfter(primaryModelAddress);
				if (tempModelAddressAfter == null) {
					continue;
				}
				// Convert the tempModelAddress back to address for this model so it can be compared.
				Program tempModelProgram = tempModel.getProgram();
				Address addressAfter = (program == tempModelProgram) ? tempModelAddressAfter
						: SimpleDiffUtility.getCompatibleAddress(tempModelProgram,
							tempModelAddressAfter, program);
				if (nextAddress == null || addressAfter.compareTo(nextAddress) < 0) {
					nextAddress = addressAfter;
				}
			}
			return nextAddress;
		}

		@Override
		public Address getAddressBefore(Address address) {
			Address previousAddress = null; // Previous address for this model.
			Program program = getProgram();
			Program primaryProgram = models[0].getProgram();
			Address primaryModelAddress = (program == primaryProgram) ? address
					: SimpleDiffUtility.getCompatibleAddress(program, address, primaryProgram);
			// If address is an external from the other model, then we may not be able to get 
			// an equivalent address in the primary model (i.e. primaryModelAddress may be null).
			if (primaryModelAddress == null) {
				return null;
			}
			// Important: These models can be from different programs, so must convert addresses.
			for (ListingModel tempModel : models) {
				Address tempModelAddressBefore = tempModel.getAddressBefore(primaryModelAddress);
				if (tempModelAddressBefore == null) {
					continue;
				}
				// Convert the tempModelAddress back to address for this model so it can be compared.
				Program tempModelProgram = tempModel.getProgram();
				Address addressBefore = (program == tempModelProgram) ? tempModelAddressBefore
						: SimpleDiffUtility.getCompatibleAddress(tempModelProgram,
							tempModelAddressBefore, program);
				if (previousAddress == null || addressBefore.compareTo(previousAddress) > 0) {
					previousAddress = addressBefore;
				}
			}
			return previousAddress;
		}

		@Override
		public Layout getLayout(Address thisModelAddress, boolean isGapAddress) {
			Address primaryModelAddress = (modelID == 0) ? thisModelAddress
					: SimpleDiffUtility.getCompatibleAddress(getProgram(), thisModelAddress,
						models[0].getProgram());
			MultiLayout ml = getMultiLayout(primaryModelAddress, isGapAddress);
			if (ml != null) {
				return ml.getLayout(modelID);
			}
			return null;
		}

		@Override
		public void addListener(ListingModelListener listener) {
			addLayoutListener(listener);
		}

		@Override
		public void removeListener(ListingModelListener listener) {
			removeLayoutListener(listener);
		}

		@Override
		public Program getProgram() {
			return models[modelID].getProgram();
		}

		@Override
		public boolean isOpen(Data data) {
			return models[modelID].isOpen(data);
		}

		@Override
		public void toggleOpen(Data data) {
			models[modelID].toggleOpen(data);
		}

		@Override
		public void openData(Data data) {
			models[modelID].openData(data);
		}

		@Override
		public void openAllData(Data data, TaskMonitor monitor) {
			models[modelID].openAllData(data, null);
		}

		@Override
		public void openAllData(AddressSetView addresses, TaskMonitor monitor) {
			models[modelID].openAllData(addresses, null /* why null? */);
		}

		@Override
		public void closeAllData(Data data, TaskMonitor monitor) {
			models[modelID].closeAllData(data, null);
		}

		@Override
		public void closeAllData(AddressSetView addresses, TaskMonitor monitor) {
			models[modelID].closeAllData(addresses, null /* why null? */);
		}

		@Override
		public void closeData(Data data) {
			models[modelID].closeData(data);
		}

		@Override
		public AddressSetView getAddressSet() {
			// The returned address set must be composed of addresses that are from 
			// address spaces in the program associated with the modelID.
			// The model's getAddressSet() uses the addrSet that was passed in to limit 
			// this address set to be compatible with the program for the modelID.
			return DiffUtility.getCompatibleAddressSet(primaryAddrSet, getProgram());
		}

		@Override
		public boolean isClosed() {
			for (ListingModel model : models) {
				if (model.isClosed()) {
					return true;
				}
			}
			return false;
		}

		@Override
		public void setFormatManager(FormatManager formatManager) {
			for (ListingModel model : models) {
				model.setFormatManager(formatManager);
			}
		}

		@Override
		public AddressSet adjustAddressSetToCodeUnitBoundaries(AddressSet addressSet) {
			return models[modelID].adjustAddressSetToCodeUnitBoundaries(addressSet);
		}

	}

	/**
	 * @see ghidra.app.util.viewer.format.FormatModelListener#formatModelAdded(ghidra.app.util.viewer.format.FieldFormatModel)
	 */
	@Override
	public void formatModelAdded(FieldFormatModel model) {
		dataChanged(true);
	}

	/**
	 * @see ghidra.app.util.viewer.format.FormatModelListener#formatModelRemoved(ghidra.app.util.viewer.format.FieldFormatModel)
	 */
	@Override
	public void formatModelRemoved(FieldFormatModel model) {
		dataChanged(true);
	}

	/**
	 * @see ghidra.app.util.viewer.format.FormatModelListener#formatModelChanged(ghidra.app.util.viewer.format.FieldFormatModel)
	 */
	@Override
	public void formatModelChanged(FieldFormatModel model) {
		modelSizeChanged();
	}

	/**
	 * Returns the ListingModel for the program with the indicated index.
	 * @param index the index indicating which program's model to get.
	 * @return the program's ListingModel.
	 */
	public ListingModel getModel(int index) {
		return models[index];
	}

	public void setAddressTranslator(AddressTranslator translator) {
		for (int i = 0; i < alignedModels.length; i++) {
			if (models[i] instanceof ListingModelConverter) {
				ListingModelConverter model = (ListingModelConverter) models[i];
				model.setAddressTranslator(translator);
			}
		}

	}

	/**
	 * Sets the address set for this MultiListingLayoutModel
	 * 
	 * @param view the current address set, which must be compatible with the 
	 * primary program and listingModel
	 */
	public void setAddressSet(AddressSetView view) {
		primaryAddrSet = view;
		modelSizeChanged();
	}
}
