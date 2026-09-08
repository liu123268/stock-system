import { ref } from 'vue'
import { defineStore } from 'pinia'
import {
  addWorkspaceStock,
  listWorkspaceStocks,
  searchStocks,
  type AddWorkspaceStockRequestDTO,
  type StockSearchResponseVO,
  type WorkspaceStockListResponseVO,
} from '@/api/stock'

export const useStockStore = defineStore('stock', () => {
  const items = ref<WorkspaceStockListResponseVO[]>([])
  const listLoading = ref(false)
  const listError = ref('')
  const searchResults = ref<StockSearchResponseVO[]>([])
  const searchLoading = ref(false)
  const searchError = ref('')
  const submitLoading = ref(false)
  const submitError = ref('')

  async function loadList(workspaceId: number) {
    listLoading.value = true
    listError.value = ''
    try {
      const result = (await listWorkspaceStocks(workspaceId)).data.data
      items.value = result
      return result
    } catch (error) {
      listError.value = error instanceof Error ? error.message : '共享自选加载失败'
      throw error
    } finally {
      listLoading.value = false
    }
  }

  async function search(keyword: string) {
    searchLoading.value = true
    searchError.value = ''
    try {
      const result = (await searchStocks(keyword.trim())).data.data
      searchResults.value = result
      return result
    } catch (error) {
      searchError.value = error instanceof Error ? error.message : '股票搜索失败'
      throw error
    } finally {
      searchLoading.value = false
    }
  }

  async function add(workspaceId: number, request: AddWorkspaceStockRequestDTO) {
    submitLoading.value = true
    submitError.value = ''
    try {
      const result = (await addWorkspaceStock(workspaceId, request)).data.data
      await loadList(workspaceId)
      return result
    } catch (error) {
      submitError.value = error instanceof Error ? error.message : '添加股票失败'
      throw error
    } finally {
      submitLoading.value = false
    }
  }

  function clearSearch() {
    searchResults.value = []
    searchError.value = ''
  }

  function clearAll() {
    items.value = []
    searchResults.value = []
    listError.value = ''
    searchError.value = ''
    submitError.value = ''
    listLoading.value = false
    searchLoading.value = false
    submitLoading.value = false
  }

  return {
    items,
    listLoading,
    listError,
    searchResults,
    searchLoading,
    searchError,
    submitLoading,
    submitError,
    loadList,
    search,
    add,
    clearSearch,
    clearAll,
  }
})
