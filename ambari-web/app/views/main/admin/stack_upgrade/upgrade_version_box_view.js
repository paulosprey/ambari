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
var stringUtils = require('utils/string_utils');

App.UpgradeVersionBoxView = Em.View.extend({
  templateName: require('templates/main/admin/stack_upgrade/upgrade_version_box'),
  classNames: ['span4', 'version-box'],
  classNameBindings: ['versionClass'],

  /**
   * @type {string}
   * @constant
   */
  PROGRESS_STATUS: 'IN_PROGRESS',

  /**
   * progress of version installation
   * @type {number}
   */
  installProgress: function() {
    var requestId = App.get('testMode') ? 1 : App.db.get('repoVersionInstall', 'id')[0];
    var installRequest = App.router.get('backgroundOperationsController.services').findProperty('id', requestId);
    return (installRequest) ? installRequest.get('progress') : 0;
  }.property('App.router.backgroundOperationsController.services.@each.progress'),

  /**
   * @type {string}
   */
  versionClass: function () {
    return this.get('content.status') == 'CURRENT'
      ? 'current-version-box' : '';
  }.property('content.status'),

  /**
   * @type {boolean}
   */
  isOutOfSync: function () {
    return this.get('content.status') == 'OUT_OF_SYNC';
  }.property('content.status'),

  /**
   * map containing version (id, label)
   * this is used as param for <code>showHosts<code> method
   * @type {Object}
   */
  versionStateMap: {
    'current': {
      'id': 'current',
      'label': Em.I18n.t('admin.stackVersions.hosts.popup.header.current')
    },
    'installed': {
      'id': 'installed',
      'label': Em.I18n.t('admin.stackVersions.hosts.popup.header.installed')
    },
    'not_installed': {
      'id': 'installing',
      'label': Em.I18n.t('admin.stackVersions.hosts.popup.header.not_installed')
    }
  },

  /**
   * @type {object}
   * @default null
   */
  content: null,

  /**
   * object that describes how content should be displayed
   * @type {Em.Object}
   */
  stateElement: function () {
    var currentVersion = this.get('controller.currentVersion');
    var upgradeVersion = this.get('controller.upgradeVersion');
    var status = this.get('content.status');
    var element = Em.Object.create({
      status: status,
      isInstalling: function () {
        return this.get('status') === 'INSTALLING';
      }.property('status')
    });

    if (status === 'CURRENT') {
      element.set('isLabel', true);
      element.set('text', Em.I18n.t('common.current'));
      element.set('class', 'label label-success');
    } else if (['INIT', 'INSTALL_FAILED', 'OUT_OF_SYNC'].contains(status)) {
      element.set('isButton', true);
      element.set('text', Em.I18n.t('admin.stackVersions.version.installNow'));
      element.set('action', 'installRepoVersionConfirmation');
    } else if (status === 'INSTALLING') {
      element.set('iconClass', 'icon-cog');
      element.set('isLink', true);
      element.set('text', Em.I18n.t('hosts.host.stackVersions.status.installing'));
      element.set('action', 'showProgressPopup');
    } else if (status === 'INSTALLED') {
      if (stringUtils.compareVersions(this.get('content.repositoryVersion'), currentVersion.repository_version) === 1) {
        element.set('isButton', true);
        element.set('text', Em.I18n.t('admin.stackVersions.version.performUpgrade'));
        element.set('action', 'runPreUpgradeCheck');
      } else {
        element.set('iconClass', 'icon-ok');
        element.set('isLink', true);
        element.set('text', Em.I18n.t('common.installed'));
        element.set('action', null);
      }
    } else if (['UPGRADING', 'UPGRADE_FAILED', 'UPGRADED'].contains(status)) {
      element.set('isLink', true);
      element.set('action', 'openUpgradeDialog');
      if (['HOLDING', 'HOLDING_FAILED', 'HOLDING_TIMEDOUT'].contains(App.get('upgradeState'))) {
        element.set('iconClass', 'icon-pause');
        if (this.get('controller.isDowngrade')) {
          element.set('text', Em.I18n.t('admin.stackVersions.version.downgrade.pause'));
        } else {
          element.set('text', Em.I18n.t('admin.stackVersions.version.upgrade.pause'));
        }
      } else {
        element.set('iconClass', 'icon-cog');
        if (this.get('controller.isDowngrade')) {
          element.set('text', Em.I18n.t('admin.stackVersions.version.downgrade.running'));
        } else {
          element.set('text', Em.I18n.t('admin.stackVersions.version.upgrade.running'));
        }
      }
    }
    return element;
  }.property('content.status', 'controller.isDowngrade'),

  didInsertElement: function () {
    App.tooltip($('.link-tooltip'), {title: Em.I18n.t('admin.stackVersions.version.linkTooltip')});
    App.tooltip($('.hosts-tooltip'), {title: Em.I18n.t('admin.stackVersions.version.hostsTooltip')});
    App.tooltip($('.empty-hosts-tooltip'), {title: Em.I18n.t('admin.stackVersions.version.emptyHostsTooltip')});
    App.tooltip($('.out-of-sync-badge'), {title: Em.I18n.t('hosts.host.stackVersions.status.out_of_sync')});
  },

  /**
   * run custom action of controller
   * @param {object} event
   */
  runAction: function (event) {
    var stateElement = event.context;
    if (stateElement.get('action')) {
      this.get('controller')[stateElement.get('action')](this.get('content'));
    }
  },

  /**
   * show popup with repositories to edit
   * @return {App.ModalPopup}
   */
  editRepositories: function () {
    var self = this;
    var repoRecord = App.RepositoryVersion.find(this.get('content.id'));
    //make deep copy of repoRecord
    var repo = Em.Object.create({
      repoVersionId: repoRecord.get('id'),
      displayName: repoRecord.get('displayName'),
      repositoryVersion: repoRecord.get('displayName'),
      operatingSystems: repoRecord.get('operatingSystems').map(function (os) {
        return Em.Object.create({
          osType: os.get('osType'),
          isSelected: true,
          repositories: os.get('repositories').map(function (repository) {
            return Em.Object.create({
              repoName: repository.get('repoName'),
              repoId: repository.get('repoId'),
              baseUrl: repository.get('baseUrl'),
              hasError: false
            });
          })
        });
      })
    });

    return App.ModalPopup.show({
      classNames: ['repository-list', 'sixty-percent-width-modal'],
      skipValidation: false,
      autoHeight: false,
      hasErrors: false,
      bodyClass: Ember.View.extend({
        content: repo,
        skipCheckBox: Ember.Checkbox.extend({
          classNames: ["align-checkbox"],
          change: function() {
            this.get('parentView.content.operatingSystems').forEach(function(os) {
              if (Em.get(os, 'repositories.length') > 0) {
                os.get('repositories').forEach(function(repo) {
                  Em.set(repo, 'hasError', false);
                })
              }
            });
          }
        }),
        templateName: require('templates/main/admin/stack_upgrade/edit_repositories'),
        didInsertElement: function () {
          App.tooltip($("[rel=skip-validation-tooltip]"), {placement: 'right'});
        }
      }),
      header: Em.I18n.t('common.repositories'),
      primary: Em.I18n.t('common.save'),
      disablePrimary: !(App.get('isAdmin') && !App.get('isOperator')),
      onPrimary: function () {
        var self = this;
        App.get('router.mainAdminStackAndUpgradeController').saveRepoOS(repo, this.get('skipValidation')).done(function(data){
          if (data.length > 0) {
            self.set('hasErrors', true);
          } else {
            self.hide();
          }
        })
      }
    });
  },

  /**
   * shows popup with listed hosts wich has current state of hostStackVersion
   * @param {object} event
   * @returns {App.ModalPopup}
   * @method showHostsListPopup
   */
  showHosts: function (event) {
    var status = event.contexts[0];
    var version = event.contexts[1];
    var hosts = event.contexts[2];
    var self = this;
    if (hosts.length) {
      return App.ModalPopup.show({
        bodyClass: Ember.View.extend({
          title: Em.I18n.t('admin.stackVersions.hosts.popup.title').format(version, status.label, hosts.length),
          hosts: hosts,
          template: Em.Handlebars.compile('<h4>{{view.title}}</h4><div class="limited-height-2">{{#each view.hosts}}<div>{{this}}</div>{{/each}}</div>')
        }),
        header: Em.I18n.t('admin.stackVersions.hosts.popup.header').format(status.label),
        primary: Em.I18n.t('admin.stackVersions.hosts.popup.primary'),
        secondary: Em.I18n.t('common.close'),
        onPrimary: function () {
          this.hide();
          self.filterHostsByStack(version, status.id);
        }
      });
    }
  },

  /**
   * goes to the hosts page with content filtered by repo_version_name and repo_version_state
   * @param version
   * @param state
   * @method filterHostsByStack
   */
  filterHostsByStack: function (version, state) {
    if (!version || !state)
      return;
    App.router.get('mainHostController').filterByStack(version, state);
    App.router.get('mainHostController').set('showFilterConditionsFirstLoad', true);
    App.router.transitionTo('hosts.index');
  }
});
