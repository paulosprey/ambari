/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

var App = require('app');
var filters = require('views/common/filter_view');
var sort = require('views/common/sort_view');

App.MainHostStackVersionsView = App.TableView.extend({
  templateName: require('templates/main/host/stack_versions'),
  classNames: ['host-tab-content'],

  /**
   * @type {Ember.Object}
   */
  host: function () {
    return App.router.get('mainHostDetailsController.content');
  }.property('App.router.mainHostDetailsController.content'),

  /**
   * @type {Ember.Array}
   */
  content: function () {
    return this.get('host.stackVersions');
  }.property('host.stackVersions'),

  /**
   * return filtered number of all content number information displayed on the page footer bar
   * @returns {string}
   */
  filteredContentInfo: function () {
    return this.t('hosts.host.stackVersions.table.filteredInfo').format(this.get('filteredCount'), this.get('totalCount'));
  }.property('filteredCount', 'totalCount'),

  /**
   * @type {Ember.View}
   */
  sortView: sort.wrapperView,

  /**
   * @type {Ember.View}
   */
  stackSort: sort.fieldView.extend({
    column: 1,
    name: 'stack',
    displayName: Em.I18n.t('common.stack'),
    type: 'version'
  }),

  /**
   * @type {Ember.View}
   */
  repoVersionSort: sort.fieldView.extend({
    column: 2,
    name: 'repoVersion',
    displayName: Em.I18n.t('common.version'),
    type: 'version'
  }),

  /**
   * @type {Ember.View}
   */
  statusSort: sort.fieldView.extend({
    column: 3,
    name: 'status',
    displayName: Em.I18n.t('common.status')
  }),

  /**
   * Filter view for stackName column
   * Based on <code>filters</code> library
   * @type {Ember.View}
   */
  stackFilterView: filters.createSelectView({
    column: 1,
    fieldType: 'filter-input-width',
    content: function () {
      return [
        {
          value: '',
          label: Em.I18n.t('common.all')
        }
      ].concat(this.get('parentView.content').mapProperty('stack').uniq().map(function (item) {
        return {
          value: item,
          label: item
        }
      }));
    }.property('App.router.clusterController.isLoaded'),
    onChangeValue: function () {
      this.get('parentView').updateFilter(this.get('column'), this.get('value'), 'select');
    }
  }),

  /**
   * Filter view for version column
   * Based on <code>filters</code> library
   * @type {Ember.View}
   */
  repoVersionFilterView: filters.createSelectView({
    column: 2,
    fieldType: 'filter-input-width',
    content: function () {
      return [
        {
          value: '',
          label: Em.I18n.t('hosts.host.stackVersions.table.allVersions')
        }
      ].concat(this.get('parentView.content').mapProperty('repoVersion').uniq().map(function (version) {
        return {
          value: version,
          label: version
        }
      }));
    }.property('App.router.clusterController.isLoaded'),
    onChangeValue: function () {
      this.get('parentView').updateFilter(this.get('column'), this.get('value'), 'select');
    }
  }),

  /**
   * Filter view for status column
   * Based on <code>filters</code> library
   * @type {Ember.View}
   */
  statusFilterView: filters.createSelectView({
    column: 3,
    fieldType: 'filter-input-width',
    content: function () {
      return [
        {
          value: '',
          label: Em.I18n.t('common.all')
        }
      ].concat(App.HostStackVersion.statusDefinition.map(function (status) {
        return {
          value: status,
          label: App.HostStackVersion.formatStatus(status)
        }
      }));
    }.property(),
    onChangeValue: function () {
      this.get('parentView').updateFilter(this.get('column'), this.get('value'), 'select');
    }
  }),

  /**
   * show progress of installation of version on host
   */
  showInstallProgress: function (event) {
    App.router.get('mainAdminStackAndUpgradeController').showProgressPopup(event.context);
  },

  /**
   * @type {Array}
   */
  colPropAssoc: function () {
    var associations = [];
    associations[1] = 'stack';
    associations[2] = 'repoVersion';
    associations[3] = 'status';
    return associations;
  }.property()

});
